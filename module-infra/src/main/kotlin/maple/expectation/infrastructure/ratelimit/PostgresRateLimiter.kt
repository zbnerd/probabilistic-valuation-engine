package maple.expectation.infrastructure.ratelimit

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PostgreSQL 기반 Rate Limiter (Sliding Window Counter Algorithm)
 *
 * <h3>알고리즘</h3>
 * <p>Sliding Window Counter로 Redis Bucket4j를 대체합니다.
 *
 * <h4>원리</h4>
 * <ul>
 *   <li>각 키에 대해 (key, count, window_start, expires_at) 저장</li>
 *   <li>요청 시마다 count 증가, window_start 갱신</li>
 *   <li>윈도우 만료 시 count 리셋</li>
 *   <li>TTL 기반 자동 만료 (expires_at)</li>
 * </ul>
 *
 * <h3>원자적 업데이트</h3>
 * <p>INSERT ... ON CONFLICT DO UPDATE로 Race Condition 방지
 *
 * <h3>CLAUDE.md 준수</h3>
 * <ul>
 *   <li>섹션 12: LogicExecutor 패턴으로 try-catch 제거</li>
 *   <li>섹션 11: ServerBaseException으로 예외 변환</li>
 *   <li>섹션 15: 람다 3줄 초과 시 메서드 추출</li>
 * </ul>
 *
 * @since Issue #152 - Redis Rate Limiting → PostgreSQL Migration
 * @see RateLimiter Strategy Pattern Interface
 * @see RateLimitProperties Configuration Properties
 */
@Component
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PostgresRateLimiter(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: RateLimitProperties,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) : RateLimiter {

    override fun getStrategyName(): String = STRATEGY_NAME

    /**
     * 토큰 소비 시도 (Sliding Window Counter)
     *
     * <p>CLAUDE.md 섹션 12 준수: LogicExecutor 패턴
     *
     * <p>CLAUDE.md 섹션 17 준수: PostgreSQL 장애 시 Fail-Open
     *
     * @param key Rate Limit 키 (IP 또는 fingerprint)
     * @return ConsumeResult 토큰 소비 결과
     */
    @CircuitBreaker(name = "postgres-ratelimit", fallbackMethod = "tryConsumeFallback")
    override fun tryConsume(key: String): ConsumeResult {
        val context = TaskContext.of("RateLimit", "PostgresConsume", "$STRATEGY_NAME:${maskKey(key)}")
        val translator = ExceptionTranslator { e, _ ->
            RateLimitException("Failed to consume rate limit: key=${maskKey(key)}", e)
        }

        return executor.executeWithTranslation(
            { doTryConsume(key) },
            translator,
            context,
        )
    }

    // ==================== Internal Implementation ====================

    /**
     * 실제 Rate Limit 확인 및 카운터 증가
     *
     * <p>Sliding Window Counter 알고리즘 구현
     *
     * <h4>SQL 동작</h4>
     * <ol>
     *   <li>INSERT 새 레코드 (count=1)</li>
     *   <li>ON CONFLICT 시:
     *     <ul>
     *       <li>윈도우 만료 → count=1로 리셋</li>
     *       <li>윈도우 유효 → count+1 증가</li>
     *     </ul>
     *   <li>RETURNING으로 업데이트된 count 반환</li>
     * </ol>
     *
     * @param key Rate Limit 키
     * @return ConsumeResult 토큰 소비 결과
     */
    private fun doTryConsume(key: String): ConsumeResult {
        val fullKey = buildFullKey(key)
        val now = Instant.now()
        val windowStart = calculateWindowStart(now)
        val expiresAt = now.plus(properties.ip.window)

        val result = executeUpsert(fullKey, now, windowStart, expiresAt)
        val (newCount, newWindowStart) = parseUpsertResult(result)

        // 윈도우가 리셋되었는지 확인
        val wasReset = newWindowStart.isAfter(windowStart)
        val actualCount = if (wasReset) 1L else newCount

        recordMetrics(actualCount <= properties.ip.capacity)

        return if (actualCount <= properties.ip.capacity) {
            ConsumeResult.allowed(properties.ip.capacity - actualCount)
        } else {
            val retryAfterSeconds = calculateRetryAfter(newWindowStart, expiresAt)
            ConsumeResult.denied(0L, retryAfterSeconds)
        }
    }

    /**
     * INSERT ON CONFLICT DO UPDATE 실행
     *
     * <p>원자적 업데이트로 Race Condition 방지
     *
     * @param fullKey 전체 키
     * @param now 현재 시각
     * @param windowStart 윈도우 시작 시각
     * @param expiresAt 만료 시각
     * @return 업데이트 결과 [count, window_start]
     */
    private fun executeUpsert(
        fullKey: String,
        now: Instant,
        windowStart: Instant,
        expiresAt: Instant,
    ): Map<String, Any> = jdbcTemplate.queryForMap(
        UPSERT_SQL,
        fullKey,
        windowStart,
        expiresAt,
        windowStart, // window_start < ? 조건용
        windowStart, // window_start 리셋용
        expiresAt,
    )

    /**
     * UPSERT 결과 파싱
     *
     * @param result SQL 결과 맵
     * @return (count, window_start) 쌍
     */
    private fun parseUpsertResult(result: Map<String, Any>): Pair<Long, Instant> {
        val count = (result["count"] as Number).toLong()
        val windowStart = (result["window_start"] as java.sql.Timestamp).toInstant()
        return Pair(count, windowStart)
    }

    /**
     * 재시도 대기 시간 계산
     *
     * @param windowStart 윈도우 시작 시각
     * @param expiresAt 만료 시각
     * @return 재시도 대기 시간 (초)
     */
    private fun calculateRetryAfter(windowStart: Instant, expiresAt: Instant): Long {
        val now = Instant.now()
        val windowEnd = windowStart.plus(properties.ip.window)
        val retryAfter = maxOf(windowEnd.epochSecond - now.epochSecond, 1L)
        return minOf(retryAfter, properties.ip.window.seconds)
    }

    /**
     * 윈도우 시작 시각 계산
     *
     * <p>현재 시각을 윈도우 크기로 나누어 윈도우 시작 시각 계산
     *
     * @param now 현재 시각
     * @return 윈도우 시작 시각
     */
    private fun calculateWindowStart(now: Instant): Instant {
        val windowSizeSeconds = properties.ip.window.seconds
        val epochSeconds = now.epochSecond
        val windowStartEpoch = (epochSeconds / windowSizeSeconds) * windowSizeSeconds
        return Instant.ofEpochSecond(windowStartEpoch)
    }

    /**
     * 전체 키 생성
     *
     * @param key 원본 키
     * @return 전체 키
     */
    private fun buildFullKey(key: String): String = "${properties.keyPrefix}:$STRATEGY_NAME:$key"

    /**
     * 키 마스킹 (로깅용)
     *
     * @param key 원본 키
     * @return 마스킹된 키
     */
    private fun maskKey(key: String?): String {
        if (key.isNullOrEmpty() || key.length <= 4) {
            return "****"
        }
        return "****" + key.substring(key.length - 4)
    }

    /**
     * 메트릭 기록
     *
     * @param allowed 요청 허용 여부
     */
    private fun recordMetrics(allowed: Boolean) {
        val result = if (allowed) "allowed" else "denied"
        meterRegistry.counter(
            "ratelimit.postgres.consume",
            "strategy",
            STRATEGY_NAME,
            "result",
            result,
        ).increment()
    }

    // ==================== Fallback Methods ====================

    /**
     * Circuit Breaker OPEN 시 Fallback
     *
     * <p>CLAUDE.md 섹션 17 준수: Fail-Open으로 가용성 우선
     *
     * @param key Rate Limit 키
     * @param e 예외
     * @return Fail-Open 결과
     */
    private fun tryConsumeFallback(key: String, e: Throwable): ConsumeResult {
        log.warn(
            "[RateLimit-FailOpen] PostgreSQL failure, allowing request: strategy={}, key={}",
            STRATEGY_NAME,
            maskKey(key),
            e,
        )
        meterRegistry.counter("ratelimit.postgres.failopen").increment()
        return ConsumeResult.failOpen()
    }

    companion object {
        private const val STRATEGY_NAME = "postgres"

        /**
         * Sliding Window Counter UPSERT SQL
         *
         * <h4>동작 원리</h4>
         * <ol>
         *   <li>INSERT: 새 키에 대해 count=1로 초기화</li>
         *   <li>ON CONFLICT:
         *     <ul>
         *       <li>window_start < ? (윈도우 만료) → count=1로 리셋</li>
         *       <li>그렇지 않으면 → count+1 증가</li>
         *     </ul>
         *   <li>RETURNING: 업데이트된 count, window_start 반환</li>
         * </ol>
         */
        private val UPSERT_SQL = """
            INSERT INTO rate_limit (key, count, window_start, expires_at)
            VALUES (?, 1, ?::timestamp with time zone, ?::timestamp with time zone)
            ON CONFLICT (key)
            DO UPDATE SET
                count = CASE
                    WHEN rate_limit.window_start < ?::timestamp with time zone THEN 1
                    ELSE rate_limit.count + 1
                END,
                window_start = CASE
                    WHEN rate_limit.window_start < ?::timestamp with time zone THEN ?::timestamp with time zone
                    ELSE rate_limit.window_start
                END,
                expires_at = ?::timestamp with time zone
            RETURNING count, window_start, expires_at
        """.trimIndent()

        private val log = LoggerFactory.getLogger(PostgresRateLimiter::class.java)
    }
}

/**
 * Rate Limiter 예외
 *
 * <p>CLAUDE.md 섹션 11 준수: ServerBaseException 상속
 */
class RateLimitException(message: String, cause: Throwable? = null) : ServerBaseException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)

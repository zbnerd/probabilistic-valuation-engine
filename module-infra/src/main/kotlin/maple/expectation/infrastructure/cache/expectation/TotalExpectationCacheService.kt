package maple.expectation.infrastructure.cache.expectation

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.dto.v2.TotalExpectationResponse
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.stereotype.Service
import java.util.Optional

/**
 * TotalExpectationResponse 전용 캐시 서비스 (Issue #158, #24)
 *
 * P0-2 정책 (L2 장애 시에도 응답 정상 반환):
 * - L1: 가능한 한 저장 (캐시 누락 시 warn)
 * - L2: 5KB 초과면 저장 스킵
 * - L2 put 실패해도 API 실패로 전파 금지 (로그+메트릭)
 *
 * 불변식 1: 5KB 기준 = serialized bytes
 * redisSerializer.serialize(response).length 기준 (ObjectMapper 직접 사용 금지)
 *
 * Issue #24: AbstractTieredCacheService 리팩토링
 * 이 클래스는 AbstractTieredCacheService를 상속받지 않고 독립 구현을 유지합니다.
 * 이유: TotalExpectationCacheService는 L1/L2를 별도의 CacheManager로 관리하며,
 * 5KB 제한 직렬화 체크, 복잡한 저장 순서(L2→L1) 등 특수한 로직이 있어 템플릿과 호환되지 않습니다.
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
@Service
class TotalExpectationCacheService(
    @Qualifier("expectationL1CacheManager") private val l1CacheManager: CacheManager,
    @Qualifier("expectationL2CacheManager") private val l2CacheManager: CacheManager,
    @Qualifier("expectationCacheSerializer") private val redisSerializer: RedisSerializer<Any>,
    private val executor: LogicExecutor,
    meterRegistry: MeterRegistry
) {
    private val oversizeSkipCounter: Counter = Counter.builder("expectation.cache.payload.oversize.skip")
        .description("5KB 초과로 L2 캐시 저장을 스킵한 횟수")
        .register(meterRegistry)

    private val serializeFailCounter: Counter = Counter.builder("expectation.cache.serialize.fail")
        .description("캐시 직렬화 실패 횟수")
        .register(meterRegistry)

    /**
     * 캐시에서 유효한 결과 조회 (L1 → L2 순서)
     *
     * L2 hit 시 L1 warm-up 수행
     *
     * @param cacheKey 캐시 키
     * @return 캐시된 결과 (없으면 empty)
     */
    fun getValidCache(cacheKey: String): Optional<TotalExpectationResponse> {
        return executor.execute(
            {
                // 1. L1 조회
                val l1 = l1CacheManager.getCache(CACHE_NAME)
                if (l1 != null) {
                    val l1Result = l1.get(cacheKey, TotalExpectationResponse::class.java)
                    if (l1Result != null) {
                        log.info(
                            "[Cache] L1 HIT | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                            l1Result.userIgn,
                            l1Result.totalCost,
                            l1Result.items?.size ?: 0
                        )
                        return@execute Optional.of(l1Result)
                    }
                } else {
                    log.warn("[Cache] L1 unavailable | cache={}", CACHE_NAME)
                }

                // 2. L2 조회
                val l2 = l2CacheManager.getCache(CACHE_NAME)
                if (l2 != null) {
                    val l2Result = l2.get(cacheKey, TotalExpectationResponse::class.java)
                    if (l2Result != null) {
                        log.info(
                            "[Cache] L2 HIT | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                            l2Result.userIgn,
                            l2Result.totalCost,
                            l2Result.items?.size ?: 0
                        )
                        // L1 warm-up
                        if (l1 != null) {
                            l1.put(cacheKey, l2Result)
                            log.debug("[Cache] L1 warm-up completed")
                        }
                        return@execute Optional.of(l2Result)
                    }
                } else {
                    log.warn("[Cache] L2 unavailable | cache={}", CACHE_NAME)
                }

                log.info(
                    "[Cache] MISS | dto=TotalExpectationResponse | maskedKey={}",
                    StringMaskingUtils.maskCacheKey(cacheKey)
                )
                Optional.empty()
            },
            TaskContext.of("ExpectationCache", "GetValid", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

    /**
     * 캐시 저장 (P0-2 순서 수정: L2 → L1)
     *
     * P0-2 Fix: TieredCache 핵심 불변식 "L2 → L1" 준수
     * 기존 L1→L2 순서 위반 수정. L2 저장을 먼저 수행하여 L1만 stale data가 존재하는 상황을 방지.
     *
     * 저장 순서:
     * 1. Serialize + size guard
     * 2. L2 put (5KB 이하일 때)
     * 3. L1 put (L2 성공 여부와 무관 — 로컬 성능 보장)
     *
     * @param cacheKey 캐시 키
     * @param response 저장할 응답
     */
    fun saveCache(cacheKey: String, response: TotalExpectationResponse) {
        executor.executeVoidJava(
            {
                // 1) Serialize + size guard
                val size = serializeAndGetSize(cacheKey, response)
                if (size < 0) {
                    // serialize 실패 시에도 L1은 저장 (로컬 성능 보장)
                    saveToL1(cacheKey, response)
                    return@executeVoidJava
                }

                // 2) L2 put FIRST (5KB 이하일 때)
                if (size <= MAX_CACHE_BYTES) {
                    saveToL2(cacheKey, response, size)
                } else {
                    log.info("[5KB Guard] L2 skip: {} bytes > {}", size, MAX_CACHE_BYTES)
                    oversizeSkipCounter.increment()
                }

                // 3) L1 put (L2 성공 여부와 무관 — 로컬 성능 보장)
                saveToL1(cacheKey, response)
            },
            TaskContext.of("ExpectationCache", "Save", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

    /** L1 캐시에 저장 (평탄화) */
    private fun saveToL1(cacheKey: String, response: TotalExpectationResponse) {
        val l1 = l1CacheManager.getCache(CACHE_NAME)
        if (l1 != null) {
            l1.put(cacheKey, response)
            log.info(
                "[Cache] L1 SAVE | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                response.userIgn,
                response.totalCost,
                response.items?.size ?: 0
            )
        } else {
            log.warn("[Cache] L1 unavailable | cache={}", CACHE_NAME)
        }
    }

    /** Serialize 후 크기 반환 (P0-2: 실패 시 -1 반환, 예외 전파 없음) */
    private fun serializeAndGetSize(cacheKey: String, response: TotalExpectationResponse): Int {
        return executor.executeOrCatch(
            {
                val bytes = redisSerializer.serialize(response)
                bytes?.size ?: 0
            },
            { e ->
                // serialize 실패는 L2 스킵(정책) + 로그
                log.warn("[Serialize Fail] err={}", e.toString())
                log.debug("[Serialize Fail] maskedKey={}", StringMaskingUtils.maskCacheKey(cacheKey))
                serializeFailCounter.increment()
                -1 // L2 스킵 시그널
            },
            TaskContext.of("ExpectationCache", "Serialize", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

    /** L2 캐시에 저장 (P0-2: 실패해도 API 실패로 전파 금지) */
    private fun saveToL2(cacheKey: String, response: TotalExpectationResponse, size: Int) {
        val l2 = l2CacheManager.getCache(CACHE_NAME)
        if (l2 == null) {
            log.warn("[Cache] L2 unavailable | cache={}", CACHE_NAME)
            return
        }

        executor.executeOrCatch(
            {
                l2.put(cacheKey, response)
                log.info(
                    "[Cache] L2 SAVE | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={} | size={}bytes",
                    response.userIgn,
                    response.totalCost,
                    response.items?.size ?: 0,
                    size
                )
                null
            },
            { e ->
                // L2 저장 실패해도 API 실패로 전파 금지 (P0-2)
                log.warn(
                    "[Cache] L2 SAVE FAIL | dto=TotalExpectationResponse | userIgn={} | err={}",
                    response.userIgn,
                    e.toString()
                )
                null
            },
            TaskContext.of("ExpectationCache", "SaveL2", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

    /**
     * 캐시 키 생성
     *
     * 형식: expectation:v3:{ocid}:{fingerprint}:{tableVersionHash}:lv{logicVersion}
     *
     * @param ocid 캐릭터 OCID
     * @param fingerprint equipment.updatedAt epoch second (null이면 "0")
     * @param tableVersionHash 테이블 버전 해시 (URL-safe)
     * @param logicVersion 계산 로직 버전
     * @return 캐시 키
     */
    fun buildCacheKey(ocid: String, fingerprint: String, tableVersionHash: String, logicVersion: Int): String {
        return "expectation:$KEY_VERSION:$ocid:$fingerprint:$tableVersionHash:lv$logicVersion"
    }

    companion object {
        private val log = LoggerFactory.getLogger(TotalExpectationCacheService::class.java)
        private const val CACHE_NAME = "expectationResult"
        private const val MAX_CACHE_BYTES = 5 * 1024 // 5KB
        private const val KEY_VERSION = "v3"
    }
}

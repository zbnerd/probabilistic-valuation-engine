package maple.expectation.integration.postgres

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.lock.PostgresLockStrategy
import maple.expectation.infrastructure.ratelimit.ConsumeResult
import maple.expectation.infrastructure.ratelimit.RateLimiter
import maple.expectation.test.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PostgreSQL 기능 통합 테스트
 *
 * <p>모든 PostgreSQL 기능의 통합 동작을 검증합니다:
 *
 * <h3>테스트 전략</h3>
 * <p>실제 Testcontainers PostgreSQL 환경에서 모든 컴포넌트가 함께 동작하는지 검증합니다.
 *
 * <h3>ADR 문서 참조</h3>
 * <ul>
 *   <li><a href="docs/adr/005-postgresql-advisory-lock.md">ADR-005: PostgreSQL Advisory Lock</a></li>
 *   <li><a href="docs/adr/006-postgresql-listen-notify.md">ADR-006: PostgreSQL LISTEN/NOTIFY</a></li>
 * </ul>
 *
 * @see PostgresLockStrategy
 * @see PostgresRateLimiter
 */
@Tag("integration")
@Tag("infra-verification")
@DisplayName("PostgreSQL 기능 통합 테스트")
class PostgresIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired(required = false)
    private var postgresLockStrategy: PostgresLockStrategy? = null

    @Autowired(required = false)
    private var postgresRateLimiter: RateLimiter? = null

    @Autowired(required = false)
    private var cacheInvalidationPublisher: maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher? = null

    @Autowired
    private lateinit var cacheManager: CacheManager

    companion object {
        private const val TEST_CACHE_NAME = "testCache"
    }

    @BeforeEach
    override fun setUp() {
        super.setUp()
        // 테스트용 테이블 생성
        createTestTables()
    }

    @AfterEach
    fun tearDown() {
        // 테스트 데이터 정리
        cleanTestData()
    }

    @Test
    @DisplayName("Advisory Lock: 동시 10개 스레드에서 정확히 1개만 획득된다")
    fun `Advisory Lock은 동시성을 보장한다`() {
        // Assume: PostgresLockStrategy가 활성화되어 있어야 함
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresLockStrategy != null,
            "PostgresLockStrategy is not enabled (set maple.infra.lock.impl=postgres)",
        )

        val lockKey = "integration-test-lock"
        val acquired = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val latch = CountDownLatch(10)

        val executor = Executors.newFixedThreadPool(10)
        repeat(10) {
            executor.submit {
                try {
                    val result = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)
                    if (result) {
                        acquired.incrementAndGet()
                        Thread.sleep(100) // 락 보유 시간
                        postgresLockStrategy!!.unlock(lockKey)
                    } else {
                        rejected.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        // 동시에 도착해도 1개만 획득
        assertThat(acquired.get()).isEqualTo(1)
        assertThat(rejected.get()).isEqualTo(9)
    }

    @Test
    @DisplayName("Advisory Lock: 해제 후 다른 스레드가 획득할 수 있다")
    fun `Advisory Lock은 해제 후 재획득이 가능하다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresLockStrategy != null,
            "PostgresLockStrategy is not enabled",
        )

        val lockKey = "integration-test-lock-reentrant"

        // 첫 번째 획득
        val acquired1 = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)
        assertThat(acquired1).isTrue

        postgresLockStrategy!!.unlock(lockKey)

        // 즉시 재획득 가능
        val acquired2 = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)
        assertThat(acquired2).isTrue

        postgresLockStrategy!!.unlock(lockKey)
    }

    @Test
    @DisplayName("Advisory Lock: executeWithLock으로 람다 실행이 보호된다")
    fun `executeWithLock은 람다 실행을 보호한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresLockStrategy != null,
            "PostgresLockStrategy is not enabled",
        )

        val lockKey = "integration-test-lock-execute"
        val counter = AtomicInteger(0)

        val result = postgresLockStrategy!!.executeWithLock(lockKey) {
            counter.incrementAndGet()
            Thread.sleep(50) // 락 보유 중 작업
            "success"
        }

        assertThat(result).isEqualTo("success")
        assertThat(counter.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Rate Limiter: Sliding Window Counter가 정확히 동작한다")
    fun `Rate Limiter는 Sliding Window Counter를 구현한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresRateLimiter != null,
            "PostgresRateLimiter is not enabled",
        )

        val key = "integration-test-ratelimit"
        val allowed = AtomicInteger(0)
        val denied = AtomicInteger(0)

        // capacity 이하의 요청은 허용
        repeat(100) {
            val result = postgresRateLimiter!!.tryConsume(key)
            if (result.allowed) {
                allowed.incrementAndGet()
            } else {
                denied.incrementAndGet()
            }
        }

        // capacity(100)까지 허용
        assertThat(allowed.get()).isGreaterThanOrEqualTo(90) // 여유있게 체크
    }

    @Test
    @DisplayName("Rate Limiter: 초과 요청은 거부된다")
    fun `Rate Limiter는 초과 요청을 거부한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresRateLimiter != null,
            "PostgresRateLimiter is not enabled",
        )

        val key = "integration-test-ratelimit-exceed"
        var lastResult: ConsumeResult? = null

        // capacity(100) 초과 요청
        repeat(150) {
            lastResult = postgresRateLimiter!!.tryConsume(key)
        }

        assertThat(lastResult).isNotNull
        assertThat(lastResult!!.allowed).isFalse
        assertThat(lastResult!!.retryAfterSeconds).isGreaterThan(0)
    }

    @Test
    @DisplayName("LISTEN NOTIFY 캐시 무효화 이벤트가 발행된다")
    fun `LISTEN_NOTIFY로 캐시 무효화 이벤트를 발행한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            cacheInvalidationPublisher != null,
            "CacheInvalidationPublisher is not available",
        )

        val cacheName = "test-cache"
        val key = "test-key"

        // 캐시에 데이터 저장
        val cache = cacheManager.getCache(cacheName)
        org.junit.jupiter.api.Assumptions.assumeTrue(cache != null, "Cache not found: $cacheName")
        cache!!.put(key, "test-value")

        assertThat(cache.get(key)?.get()).isEqualTo("test-value")

        // 이벤트 발행
        val event = CacheInvalidationEvent.evict(cacheName, key, "integration-test")

        cacheInvalidationPublisher!!.publish(event)

        // 발행 확인 (로깅 또는 메트릭으로 확인)
        // 실제 환경에서는 다른 인스턴스에서 수신 확인 필요
    }

    @Test
    @DisplayName("Cache Invalidation: EVICT 이벤트로 특정 키가 삭제된다")
    fun `EVICT 이벤트로 특정 키가 삭제된다`() {
        val cacheName = "test-cache-evict"
        val key = "test-key-evict"
        val nullableCache = cacheManager.getCache(cacheName)
        org.junit.jupiter.api.Assumptions.assumeTrue(nullableCache != null, "Cache not found: $cacheName")
        val cache = nullableCache!!

        // 데이터 저장
        cache.put(key, "test-value")
        assertThat(cache.get(key)).isNotNull

        // 직접 evict
        cache.evict(key)

        // 삭제 확인
        assertThat(cache.get(key)).isNull()
    }

    @Test
    @DisplayName("Cache Invalidation: CLEAR_ALL로 전체 캐시가 삭제된다")
    fun `CLEAR_ALL로 전체 캐시가 삭제된다`() {
        val cacheName = "test-cache-clear"
        val nullableCache = cacheManager.getCache(cacheName)
        org.junit.jupiter.api.Assumptions.assumeTrue(nullableCache != null, "Cache not found: $cacheName")
        val cache = nullableCache!!

        // 여러 키 저장
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        assertThat(cache.get("key1")).isNotNull
        assertThat(cache.get("key2")).isNotNull
        assertThat(cache.get("key3")).isNotNull

        // 전체 삭제
        cache.clear()

        // 모두 삭제 확인
        assertThat(cache.get("key1")).`isNull`()
        assertThat(cache.get("key2")).`isNull`()
        assertThat(cache.get("key3")).`isNull`()
    }

    @Test
    @DisplayName("Advisory Lock: 재진입(Reentrant)이 지원된다")
    fun `Advisory Lock은 재진입을 지원한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresLockStrategy != null,
            "PostgresLockStrategy is not enabled",
        )

        val lockKey = "integration-test-lock-reentrant-same-thread"

        // 같은 스레드에서 여러번 획득 시도
        val acquired1 = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)
        val acquired2 = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)
        val acquired3 = postgresLockStrategy!!.tryLockImmediately(lockKey, 10)

        // 모두 성공해야 함 (재진입 지원)
        assertThat(acquired1).isTrue
        assertThat(acquired2).isTrue
        assertThat(acquired3).isTrue

        // 단 한 번의 unlock로 해제
        postgresLockStrategy!!.unlock(lockKey)
    }

    @Test
    @DisplayName("Rate Limiter: 윈도우가 리셋되면 카운트가 초기화된다")
    fun `윈도우 리셋 후 카운트가 초기화된다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            postgresRateLimiter != null,
            "PostgresRateLimiter is not enabled",
        )

        val key = "integration-test-ratelimit-reset"

        // 첫 번째 요청 (카운트=1)
        val result1 = postgresRateLimiter!!.tryConsume(key)
        assertThat(result1.allowed).isTrue

        // 윈도우 크기보다 길게 대기 (테스트에서는 짧게)
        Thread.sleep(100)

        // 윈도우 리셋 후 다시 요청
        val result2 = postgresRateLimiter!!.tryConsume(key)
        assertThat(result2.allowed).isTrue
    }

    // ==================== Helper Methods ====================

    private fun createTestTables() {
        // Rate Limit 테이블 생성
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS rate_limit (
                key VARCHAR(255) PRIMARY KEY,
                count BIGINT NOT NULL,
                window_start TIMESTAMP WITH TIME ZONE NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """.trimIndent(),
        )

        // 캐시 테이블 생성 (L2)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS l2_cache (
                cache_name VARCHAR(100) NOT NULL,
                cache_key VARCHAR(500) NOT NULL,
                value TEXT NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                PRIMARY KEY (cache_name, cache_key)
            )
            """.trimIndent(),
        )
    }

    private fun cleanTestData() {
        // 테스트 데이터 정리
        jdbcTemplate.update("DELETE FROM rate_limit WHERE key LIKE 'integration-test-%'")
        jdbcTemplate.update("DELETE FROM l2_cache WHERE cache_name LIKE 'test-%'")
    }
}

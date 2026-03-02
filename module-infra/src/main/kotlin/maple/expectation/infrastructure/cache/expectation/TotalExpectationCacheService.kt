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
 * P0-2 정책 (L2 장애 시에도 응답 정상 반환)
 * - L1: 가능한 한 저장 (캐시 누락 시 warn)
 * - L2: 5KB 초과면 저장 스킵
 * - L2 put 실패해도 API 실패로 전파 금지 (로그+메트릭)
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
                        l1?.put(cacheKey, l2Result)
                        log.debug("[Cache] L1 warm-up completed")
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
     * 캐시 저장 (P0-2 순서: L2 → L1)
     */
    fun saveCache(cacheKey: String, response: TotalExpectationResponse) {
        executor.executeVoidJava(
            {
                // 1) Serialize + size guard
                val size = serializeAndGetSize(cacheKey, response)
                if (size < 0) {
                    // serialize 실패 시에도 L1은 저장
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

                // 3) L1 put
                saveToL1(cacheKey, response)
            },
            TaskContext.of("ExpectationCache", "Save", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

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

    private fun serializeAndGetSize(cacheKey: String, response: TotalExpectationResponse): Int {
        return executor.executeOrCatch(
            {
                val bytes = redisSerializer.serialize(response)
                bytes?.size ?: 0
            },
            { e ->
                log.warn("[Serialize Fail] err={}", e.toString())
                log.debug("[Serialize Fail] maskedKey={}", StringMaskingUtils.maskCacheKey(cacheKey))
                serializeFailCounter.increment()
                -1
            },
            TaskContext.of("ExpectationCache", "Serialize", StringMaskingUtils.maskCacheKey(cacheKey))
        )
    }

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
     * 형식: expectation:v3:{ocid}:{fingerprint}:{tableVersionHash}:lv{logicVersion}
     */
    fun buildCacheKey(
        ocid: String,
        fingerprint: String,
        tableVersionHash: String,
        logicVersion: Int
    ): String {
        return "expectation:$KEY_VERSION:$ocid:$fingerprint:$tableVersionHash:lv$logicVersion"
    }

    companion object {
        private val log = LoggerFactory.getLogger(TotalExpectationCacheService::class.java)
        private const val CACHE_NAME = "expectationResult"
        private const val MAX_CACHE_BYTES = 5 * 1024 // 5KB
        private const val KEY_VERSION = "v3"
    }
}

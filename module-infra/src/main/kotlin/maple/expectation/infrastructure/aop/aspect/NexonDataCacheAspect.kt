package maple.expectation.infrastructure.aop.aspect

import maple.expectation.core.domain.stat.StatType
import maple.expectation.error.exception.ExternalServiceException
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.aop.context.SkipEquipmentL2CacheContext
import maple.expectation.infrastructure.cache.port.EquipmentCache
import maple.expectation.infrastructure.config.NexonApiProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import org.slf4j.LoggerFactory
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RCountDownLatch
import org.redisson.api.RedissonClient
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Nexon API Cache AOP - 분산 캐시 전략 (Leader-Follower 패턴)
 */
@Aspect
@Component
@Order(1)
class NexonDataCacheAspect(
    private val cacheService: EquipmentCache,
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val nexonApiProperties: NexonApiProperties
) {

    companion object {
        private val log = LoggerFactory.getLogger(NexonDataCacheAspect::class.java)
    }

    @Around(
        "@annotation(maple.expectation.infrastructure.aop.annotation.NexonDataCache) && args(ocid, ..)"
    )
    fun handleNexonCache(joinPoint: ProceedingJoinPoint, ocid: String): Any {
        val signature = joinPoint.signature as MethodSignature
        val returnType = signature.returnType

        return getCachedResult(ocid, returnType)
            .orElseGet { executeDistributedStrategy(joinPoint, ocid, returnType) }
    }

    private fun executeDistributedStrategy(
        joinPoint: ProceedingJoinPoint,
        ocid: String,
        returnType: Class<*>
    ): Any {
        val latchKey = "latch:eq:$ocid"
        val latch = redissonClient.getCountDownLatch(latchKey)

        return if (latch.trySetCount(1)) {
            val initialTtl = nexonApiProperties.latchInitialTtlSeconds.toLong()
            redissonClient.keys.expire(latchKey, initialTtl, TimeUnit.SECONDS)
            executeAsLeader(joinPoint, ocid, returnType, latch)
        } else {
            executeAsFollower(ocid, returnType, latch)
        }
    }

    private fun executeAsLeader(
        joinPoint: ProceedingJoinPoint,
        ocid: String,
        returnType: Class<*>,
        latch: RCountDownLatch
    ): Any {
        return executor.execute(
            { fetchAndCacheData(joinPoint, ocid, returnType, latch) },
            TaskContext.of("NexonCache", "Leader", ocid)
        )
    }

    private fun fetchAndCacheData(
        joinPoint: ProceedingJoinPoint,
        ocid: String,
        returnType: Class<*>,
        latch: RCountDownLatch
    ): Any {
        val result = joinPoint.proceed()

        if (result is CompletableFuture<*>) {
            return handleAsyncResult(result as CompletableFuture<*>, ocid, latch)
        }

        // 동기 경로
        return executor.executeWithFinally(
            { saveAndWrap(result, ocid, returnType) },
            { finalizeLatch(latch) },
            TaskContext.of("NexonCache", "SyncCache", ocid)
        )
    }

    /** 비동기 결과 처리 (평탄화) */
    private fun handleAsyncResult(
        future: CompletableFuture<*>,
        ocid: String,
        latch: RCountDownLatch
    ): Any {
        val skipContextSnap = SkipEquipmentL2CacheContext.snapshot() // V5: MDC 기반

        return future.handle { res, ex ->
            executor.executeWithFinally(
                { processAsyncCallback(res, ex, ocid, skipContextSnap ?: "") },
                { finalizeLatch(latch) },
                TaskContext.of("NexonCache", "AsyncCache", ocid)
            )
        }
    }

    /** 비동기 콜백 처리 로직 (평탄화) */
    private fun processAsyncCallback(
        res: Any?,
        ex: Throwable?,
        ocid: String,
        skipContextSnap: String
    ): Any {
        val before = SkipEquipmentL2CacheContext.snapshot() // V5: MDC 기반
        SkipEquipmentL2CacheContext.restore(skipContextSnap)

        return executor.executeWithFinally(
            { doProcessAsyncCallback(res, ex, ocid) },
            { SkipEquipmentL2CacheContext.restore(before) },
            TaskContext.of("NexonCache", "AsyncCallback", ocid)
        )
    }

    /** 비동기 콜백 핵심 로직 */
    private fun doProcessAsyncCallback(res: Any?, ex: Throwable?, ocid: String): Any {
        if (ex != null) {
            throw toRuntimeException(ex, ocid)
        }

        if (res is EquipmentResponse) {
            saveEquipmentIfAllowed(ocid, res)
        }

        @Suppress("UNCHECKED_CAST")
        return res as Any
    }

    /** Equipment 저장 (Expectation 경로 분기) */
    private fun saveEquipmentIfAllowed(ocid: String, response: EquipmentResponse) {
        if (SkipEquipmentL2CacheContext.enabled()) {
            log.debug("[NexonCache] L2 save skipped (Expectation path): $ocid")
            return
        }
        cacheService.saveCache(ocid, response)
    }

    private fun toRuntimeException(ex: Throwable, ocid: String): RuntimeException {
        // P0: Error는 즉시 전파 (OOM, StackOverflow 등)
        if (ex is Error) throw ex

        // P1: RuntimeException (BaseException 포함)은 타입 보존
        if (ex is RuntimeException) return ex

        // P2: TimeoutException → ExternalServiceException
        if (ex is TimeoutException) {
            return ExternalServiceException("NexonCache:AsyncCallback:timeout:$ocid", ex)
        }

        // P3: InterruptedException 특수 처리 - 인터럽트 플래그 복원
        if (ex is InterruptedException) {
            Thread.currentThread().interrupt()
            return InternalSystemException("NexonCache:AsyncCallback:interrupted:$ocid", ex)
        }

        // P4: 기타 Checked Exception → InternalSystemException
        return InternalSystemException("NexonCache:AsyncCallback:$ocid", ex)
    }

    private fun saveAndWrap(result: Any, ocid: String, returnType: Class<*>): Any {
        val response = result as EquipmentResponse
        // Issue #158: Expectation 경로에서는 L2 저장 스킵
        if (!SkipEquipmentL2CacheContext.enabled()) {
            cacheService.saveCache(ocid, response)
        } else {
            log.debug("[NexonCache] L2 save skipped (Expectation path): $ocid")
        }
        return wrap(response, returnType)
    }

    private fun executeAsFollower(ocid: String, returnType: Class<*>, latch: RCountDownLatch): Any {
        return executor.execute(
            {
                log.info("[Follower] 대장 완료 대기 중...: $ocid")
                val timeoutSeconds = nexonApiProperties.cacheFollowerTimeoutSeconds.toLong()
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw InternalSystemException("NexonCache Follower Timeout: $ocid")
                }

                getCachedResult(ocid, returnType)
                    .orElseThrow { InternalSystemException("NexonCache Leader Failed: $ocid") }
            },
            TaskContext.of("NexonCache", "Follower", ocid)
        )
    }

    private fun finalizeLatch(latch: RCountDownLatch) {
        latch.countDown()
        val finalizeTtl = nexonApiProperties.latchFinalizeTtlSeconds.toLong()
        redissonClient.keys.expire(latch.name, finalizeTtl, TimeUnit.SECONDS)
        log.debug("[Leader] 래치 정리 완료 ({}초 뒤 만료)", finalizeTtl)
    }

    private fun getCachedResult(ocid: String, returnType: Class<*>): Optional<Any> {
        val cached = cacheService.getValidCache(ocid)
        return if (cached != null && cached.isPresent) {
            cached.map { res -> wrap(res, returnType) }
        } else {
            Optional.ofNullable(
                if (cacheService.hasNegativeCache(ocid)) {
                    wrap(null, returnType)
                } else {
                    null
                }
            )
        }
    }

    private fun wrap(res: EquipmentResponse?, type: Class<*>): Any {
        return if (CompletableFuture::class.java.isAssignableFrom(type)) {
            CompletableFuture.completedFuture(res)
        } else {
            res!!
        }
    }
}

package maple.expectation.infrastructure.aop.aspect

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import maple.expectation.error.exception.ExternalServiceException
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.aop.context.SkipEquipmentL2CacheContext
import maple.expectation.infrastructure.cache.port.EquipmentCache
import maple.expectation.infrastructure.config.NexonApiProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.lock.LeaderElectionStrategy
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Nexon API Cache AOP - 분산 캐시 전략 (Leader-Follower 패턴)
 *
 * V5 Migration: Redis RCountDownLatch → PostgreSQL Advisory Lock
 * Uses LeaderElectionStrategy (implemented by PostgresAdvisoryLockStrategy)
 */
@Aspect
@Component
@Order(1)
class NexonDataCacheAspect(
    private val cacheService: EquipmentCache,
    private val leaderElectionStrategy: LeaderElectionStrategy,
    private val executor: LogicExecutor,
    private val nexonApiProperties: NexonApiProperties,
) {

    companion object {
        private val log = LoggerFactory.getLogger(NexonDataCacheAspect::class.java)
    }

    @Around(
        "@annotation(maple.expectation.infrastructure.aop.annotation.NexonDataCache) && args(ocid, ..)",
    )
    fun handleNexonCache(joinPoint: ProceedingJoinPoint, ocid: String): Any {
        val signature = joinPoint.signature as MethodSignature
        val returnType = signature.returnType

        return getCachedResult(ocid, returnType)
            .orElseGet { executeWithLeaderElection(joinPoint, ocid, returnType) }
    }

    private fun executeWithLeaderElection(
        joinPoint: ProceedingJoinPoint,
        ocid: String,
        returnType: Class<*>,
    ): Any {
        if (!nexonApiProperties.equipmentCacheSingleFlightEnabled) {
            return executeAsLeader(joinPoint, ocid, returnType)
        }

        val waitTimeSeconds = nexonApiProperties.cacheFollowerTimeoutSeconds

        return leaderElectionStrategy.executeWithLeaderElection(
            key = ocid,
            waitTimeSeconds = waitTimeSeconds,
            leaderTask = { executeAsLeader(joinPoint, ocid, returnType) },
            followerTask = { executeAsFollower(ocid, returnType) },
        )
    }

    private fun executeAsLeader(
        joinPoint: ProceedingJoinPoint,
        ocid: String,
        returnType: Class<*>,
    ): Any {
        log.info("[Leader] 캐시 갱신 시작: $ocid")
        val result = joinPoint.proceed()

        return if (result is CompletableFuture<*>) {
            handleAsyncResult(result as CompletableFuture<*>, ocid, returnType)
        } else {
            // 동기 경로
            executor.execute(
                { saveAndWrap(result, ocid, returnType) },
                TaskContext.of("NexonCache", "LeaderSync", ocid),
            )
        }
    }

    /** 비동기 결과 처리 (평탄화) */
    private fun handleAsyncResult(
        future: CompletableFuture<*>,
        ocid: String,
        returnType: Class<*>,
    ): Any {
        val skipContextSnap = SkipEquipmentL2CacheContext.snapshot()

        return future.handle { res, ex ->
            executor.execute(
                { processAsyncCallback(res, ex, ocid, returnType, skipContextSnap ?: "") },
                TaskContext.of("NexonCache", "AsyncCallback", ocid),
            )
        }
    }

    /** 비동기 콜백 처리 로직 (평탄화) */
    private fun processAsyncCallback(
        res: Any?,
        ex: Throwable?,
        ocid: String,
        returnType: Class<*>,
        skipContextSnap: String,
    ): Any {
        val before = SkipEquipmentL2CacheContext.snapshot()
        SkipEquipmentL2CacheContext.restore(skipContextSnap)

        return executor.executeWithFinally(
            { doProcessAsyncCallback(res, ex, ocid, returnType) },
            { SkipEquipmentL2CacheContext.restore(before) },
            TaskContext.of("NexonCache", "AsyncCallbackInner", ocid),
        )
    }

    /** 비동기 콜백 핵심 로직 */
    private fun doProcessAsyncCallback(res: Any?, ex: Throwable?, ocid: String, returnType: Class<*>): Any {
        if (ex != null) {
            throw toRuntimeException(ex, ocid)
        }

        if (res is EquipmentResponse) {
            saveEquipmentIfAllowed(ocid, res)
        }

        @Suppress("UNCHECKED_CAST")
        return wrap(res as? EquipmentResponse, returnType)
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

    private fun executeAsFollower(ocid: String, returnType: Class<*>): Any {
        log.info("[Follower] 리더 완료 후 캐시 조회: $ocid")
        return getCachedResult(ocid, returnType)
            .orElseThrow { InternalSystemException("NexonCache Leader Failed: $ocid") }
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
                },
            )
        }
    }

    private fun wrap(res: EquipmentResponse?, type: Class<*>): Any = if (CompletableFuture::class.java.isAssignableFrom(type)) {
        CompletableFuture.completedFuture(res)
    } else {
        requireNotNull(res) { "EquipmentResponse must not be null for synchronous cache wrap (ocid lookup)" }
    }
}

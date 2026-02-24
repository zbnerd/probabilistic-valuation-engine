package maple.expectation.infrastructure.aop.aspect

import lombok.RequiredArgsConstructor
import lombok.extern.slf4j.Slf4j.Slf4j
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.aop.annotation.Locked
import maple.expectation.infrastructure.aop.util.CustomSpelParser
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Slf4j
@Aspect
@Order(0)
@Component
@RequiredArgsConstructor
class LockAspect(
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
    private val spelParser: CustomSpelParser
) {

    @Around("@annotation(locked)")
    fun applyLock(joinPoint: ProceedingJoinPoint, locked: Locked): Any? {
        val key = getDynamicKey(joinPoint, locked.key)
        val waitSeconds = locked.timeUnit.toSeconds(locked.waitTime)
        val leaseSeconds = locked.timeUnit.toSeconds(locked.leaseTime)

        // ✅ TaskContext 적용: Component="Lock", Operation="Apply"
        return executor.executeOrCatch(
            { executeLockProtectedTask(joinPoint, key, waitSeconds, leaseSeconds) },
            { e -> handleLockFailure(joinPoint, key, e) },
            TaskContext.of("Lock", "Apply", key)
        )
    }

    private fun executeLockProtectedTask(
        joinPoint: ProceedingJoinPoint,
        key: String,
        waitSeconds: Long,
        leaseSeconds: Long
    ): Any {
        return lockStrategy.executeWithLock(
            key,
            waitSeconds,
            leaseSeconds,
            createLockedTask(joinPoint, key)
        )
    }

    private fun createLockedTask(joinPoint: ProceedingJoinPoint, key: String): ThrowingSupplier<Any> {
        return ThrowingSupplier {
            log.debug("🔑 [Locked Aspect] 락 획득 성공: {}", key)
            joinPoint.proceed()
        }
    }

    private fun handleLockFailure(joinPoint: ProceedingJoinPoint, key: String, e: Throwable): Any? {
        if (e is DistributedLockException) {
            log.warn("⏭️ [Locked Timeout] {} - 락 획득 실패. 직접 조회를 시도합니다.", key)
            return proceedWithoutLock(joinPoint, key)
        }
        throw InternalSystemException("DistributedLockExecution:$key", e)
    }

    private fun proceedWithoutLock(joinPoint: ProceedingJoinPoint, key: String): Any? {
        // ✅ TaskContext 적용: Component="Lock", Operation="Fallback"
        return executor.execute(
            { joinPoint.proceed() },
            TaskContext.of("Lock", "Fallback", key)
        )
    }

    private fun getDynamicKey(joinPoint: ProceedingJoinPoint, keyExpression: String): String {
        return spelParser.parse(joinPoint, keyExpression)
    }
}

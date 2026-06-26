package maple.expectation.infrastructure.lock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator

/** 락 전략 추상 클래스 (TaskContext 및 평탄화 적용) */
abstract class AbstractLockStrategy(protected val executor: LogicExecutor) : LockStrategy {

    // === ASYNC API (template-style) ===
    // Concrete impls (PostgresLockStrategy, GuavaLockStrategy) provide the
    // tryAcquireSessionLockAsync / releaseSessionLockAsync hooks. The CF chain
    // guarantees lock release on both success and failure paths via whenComplete.

    override fun <T> executeWithLockAsync(
        key: String,
        waitTime: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val lockKey = buildLockKey(key)
        val context = TaskContext.of("Lock", "ExecuteAsync", key)

        return tryAcquireSessionLockAsync(lockKey, waitTime, leaseTime, context)
            .thenCompose { lockId -> executeSuppliedTask(lockKey, lockId, supplier, context) }
    }

    private fun <T> executeSuppliedTask(
        lockKey: String,
        lockId: Long?,
        supplier: () -> CompletableFuture<T>,
        context: TaskContext,
    ): CompletableFuture<T> =
        if (lockId == null) {
            onLockFailed(lockKey)
            CompletableFuture.failedFuture(createLockFailureException(lockKey))
        } else {
            onLockAcquired(lockKey)
            supplier().whenComplete { _, _ ->
                releaseSessionLockAsync(lockKey, lockId, context)
            }
        }

    override fun <T> executeWithLockAsync(
        key: String,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> = executeWithLockAsync(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, supplier)

    override fun tryLockImmediatelyAsync(key: String, leaseTime: Long): CompletableFuture<Boolean> {
        val lockKey = buildLockKey(key)
        val context = TaskContext.of("Lock", "TryLockAsync", key)
        return tryAcquireSessionLockAsync(lockKey, 0, leaseTime, context).thenApply { it != null }
    }

    override fun unlockAsync(key: String): CompletableFuture<Void> {
        val lockKey = buildLockKey(key)
        val context = TaskContext.of("Lock", "UnlockAsync", key)
        return releaseSessionLockAsync(lockKey, null, context)
    }

    override fun <T> executeWithOrderedLocksAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val compositeKey = keys.stream().sorted().collect(Collectors.joining(":"))
        val timeoutSeconds = timeUnit.toSeconds(totalTimeout)
        return executeWithLockAsync(compositeKey, timeoutSeconds, leaseTime, supplier)
    }

    // === Abstract async hooks (concrete impls provide these) ===

    protected abstract fun tryAcquireSessionLockAsync(
        lockKey: String,
        waitTime: Long,
        leaseTime: Long,
        ctx: TaskContext,
    ): CompletableFuture<Long?>

    protected abstract fun releaseSessionLockAsync(
        lockKey: String,
        lockId: Long?,
        ctx: TaskContext,
    ): CompletableFuture<Void>

    // === SYNC API (legacy, kept for module-app compat) ===

    override fun <T> executeWithLock(key: String, waitTime: Long, leaseTime: Long, task: ThrowingSupplier<T>): T {
        val lockKey = buildLockKey(key)
        // ✅ TaskContext 정의: Component="Lock", Operation="Execute"
        val context = TaskContext.of("Lock", "Execute", key)

        return executor.executeWithTranslation(
            { this.performLockAndExecute(lockKey, waitTime, leaseTime, task, context) },
            ExceptionTranslator.forLock(),
            context,
        )
    }

    override fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T = executeWithLock(key, 10, 20, task)

    override fun unlock(key: String) {
        val lockKey = buildLockKey(key)
        // ✅ TaskContext 정의: Component="Lock", Operation="Unlock"
        val context = TaskContext.of("Lock", "Unlock", key)

        executor.executeVoidJava({ this.performUnlock(lockKey, context) }, context)
    }

    /**
     * 실제 락 획득 및 작업 실행 로직
     */
    private fun <T> performLockAndExecute(
        lockKey: String,
        waitTime: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
        context: TaskContext,
    ): T {
        // 1. 락 획득 시도
        if (!tryLock(lockKey, waitTime, leaseTime)) {
            onLockFailed(lockKey)
            throw createLockFailureException(lockKey)
        }

        // 2. 락 획득 성공 Hook
        onLockAcquired(lockKey)

        // 3. 작업 실행 + finally 블록에서 락 해제
        // ✅ TaskContext 재사용
        return executor.executeWithFinally(task, { this.performUnlock(lockKey, context) }, context)
    }

    /** 락 해제 로직 (평탄화 및 노이즈 제거) */
    private fun performUnlock(lockKey: String, context: TaskContext) {
        // 락 해제 중의 예외는 로직에 지장을 주지 않도록 executeOrDefault 또는 executeVoid로 보호
        executor.executeVoidJava(
            {
                if (shouldUnlock(lockKey)) {
                    unlockInternal(lockKey)
                    onLockReleased(lockKey)
                }
            },
            context,
        )
    }

    // ===== 추상 메서드 및 Hook 메서드는 기존과 동일하게 유지 =====

    protected abstract fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean

    protected abstract fun unlockInternal(lockKey: String)

    protected abstract fun shouldUnlock(lockKey: String): Boolean

    protected open fun buildLockKey(key: String): String = "lock:$key"

    protected open fun onLockAcquired(lockKey: String) {
        log.debug("🔓 [Lock] '{}' 획득 성공", lockKey)
    }

    protected open fun onLockFailed(lockKey: String) {
        log.warn("⏭️ [Lock] '{}' 획득 실패", lockKey)
    }

    protected open fun onLockReleased(lockKey: String) {
        log.debug("🔒 [Lock] '{}' 해제 완료", lockKey)
    }

    protected open fun createLockFailureException(lockKey: String): RuntimeException = DistributedLockException(lockKey)

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AbstractLockStrategy::class.java)
        private const val DEFAULT_WAIT_TIME = 10L
        private const val DEFAULT_LEASE_TIME = 20L
    }
}

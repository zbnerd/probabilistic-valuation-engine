package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

interface LockStrategy {

    // --- New async API (preferred) ---
    fun <T> executeWithLockAsync(
        key: String,
        waitTime: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    fun <T> executeWithLockAsync(
        key: String,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    fun tryLockImmediatelyAsync(key: String, leaseTime: Long): CompletableFuture<Boolean>

    fun unlockAsync(key: String): CompletableFuture<Void>

    fun <T> executeWithOrderedLocksAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    // --- Deprecated sync API (kept for module-app legacy; soft-deprecation) ---
    @Deprecated("Use executeWithLockAsync", ReplaceWith("executeWithLockAsync(key, waitTime, leaseTime, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithLock(key: String, waitTime: Long, leaseTime: Long, task: ThrowingSupplier<T>): T

    @Deprecated("Use executeWithLockAsync", ReplaceWith("executeWithLockAsync(key, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T

    @Deprecated("Use tryLockImmediatelyAsync")
    fun tryLockImmediately(key: String, leaseTime: Long): Boolean

    @Deprecated("Use unlockAsync")
    fun unlock(key: String)

    /**
     * [P0-N02] 다중 락 순서 보장 실행 (Legacy sync)
     *
     * 기본 구현: 알파벳순 정렬 후 단일 복합키로 결합하여 락을 획득합니다.
     * 진정한 다중 락 지원이 필요하면 구현체에서 Override하세요.
     *
     * Note: 이 default 구현은 의도적으로 deprecated된 sync `executeWithLock`를 호출합니다.
     * `executeWithOrderedLocksAsync` default로 사용 시 `task.get()`을 강제하게 되어
     * (default path에서도 caller block 발생), legacy compat을 위해 sync chain 유지합니다.
     * Concrete impl에서 `executeWithOrderedLocksAsync`를 override하여 async path를 제공하세요.
     *
     * @see executeWithOrderedLocksAsync
     */
    @Deprecated("Use executeWithOrderedLocksAsync", ReplaceWith("executeWithOrderedLocksAsync(keys, totalTimeout, timeUnit, leaseTime, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
    ): T {
        // [P1-BLUE-02] 기본 구현: 알파벳순 정렬 후 복합키로 결합
        val compositeKey = keys.stream().sorted().collect(Collectors.joining(":"))
        val timeoutSeconds = timeUnit.toSeconds(totalTimeout)
        return executeWithLock(compositeKey, timeoutSeconds, leaseTime, task)
    }
}

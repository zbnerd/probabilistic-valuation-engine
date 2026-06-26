package maple.expectation.infrastructure.lock

import com.google.common.util.concurrent.Striped
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.context.annotation.Profile

/**
 * Guava Striped Lock 전략 (테스트 환경 전용 - 100% 평탄화 완료)
 *
 * <p>주의: 이 클래스는 Spring Bean으로 등록되지 않습니다.
 * 테스트에서 필요한 경우 수동으로 생성해서 사용하세요 */
@Profile("test")
class GuavaLockStrategy(executor: LogicExecutor) : AbstractLockStrategy(executor) {

    private val locks: Striped<Lock> = Striped.lock(128)

    /**
     * Session-scoped lock registry: maps logical key -> held Lock so that
     * [releaseSessionLockAsync] knows which Stripe slot to release for
     * [tryLockImmediatelyAsync]. Test-only — in-memory map is acceptable
     * because the test profile runs single-process.
     */
    private val sessionLockHolders = ConcurrentHashMap<String, Lock>()

    /**
     * Test-only executor: not a Spring bean, so cannot inject defaultAsyncExecutor.
     * Cached pool is fine for short-lived test workloads.
     */
    private val lockExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "guava-lock-${r.hashCode()}").apply { isDaemon = true }
    }

    @Throws(Throwable::class)
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        // 부모 클래스(AbstractLockStrategy)의 템플릿 메서드에서 호출됨
        return locks.get(lockKey).tryLock(waitTime, TimeUnit.SECONDS)
    }

    override fun unlockInternal(lockKey: String) {
        locks.get(lockKey).unlock()
    }

    override fun shouldUnlock(lockKey: String): Boolean {
        // 로컬 락은 상태 체크 대신 해제 시도 시 발생하는 예외를 Executor가 처리하도록 위임
        return true
    }

    override fun buildLockKey(key: String): String = key

    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        // [패턴 3] executeOrDefault를 사용하여 try-catch 없이 즉시 획득 시도
        return executor.executeOrDefault(
            { locks.get(key).tryLock() },
            false,
            TaskContext.of("Lock", "GuavaTryImmediate", key),
        )
    }

    /**
     * [SESSION-SCOPED] Async: Poll for Guava Striped lock acquisition on the
     * test-only executor. Returns the lockKey's hashCode as a stable lockId
     * on success, or null on timeout.
     */
    override fun tryAcquireSessionLockAsync(
        lockKey: String,
        waitTime: Long,
        leaseTime: Long,
        ctx: TaskContext,
    ): CompletableFuture<Long?> = CompletableFuture.supplyAsync({
        val deadline = System.currentTimeMillis() + waitTime * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) {
                throw maple.expectation.error.exception.DistributedLockException(
                    "Interrupted while waiting for lock: $lockKey",
                )
            }
            val lock = locks.get(lockKey)
            if (lock.tryLock()) {
                sessionLockHolders[lockKey] = lock
                return@supplyAsync lockKey.hashCode().toLong()
            }
            // VT-friendly: Thread.sleep on virtual thread does not pin carrier.
            Thread.sleep(POLL_INTERVAL_MS)
        }
        null
    }, lockExecutor)

    /**
     * [SESSION-SCOPED] Async: Release previously acquired session-scoped lock.
     * Called from whenComplete — must never throw.
     *
     * Justified: invoked from `whenComplete` callbacks inside CompletableFuture
     * chains. LogicExecutor cannot be used inside `whenComplete` (it expects a
     * synchronous caller context), so a thrown exception from unlock() would
     * complete the outer CF exceptionally AFTER the supplier has already returned.
     * Logging + swallow is the only safe action.
     */
    override fun releaseSessionLockAsync(
        lockKey: String,
        lockId: Long?,
        ctx: TaskContext,
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        val lock = sessionLockHolders.remove(lockKey) ?: return@runAsync
        try {
            lock.unlock()
            log.debug("🔓 [Guava Lock] Unlocked key: {}", lockKey)
        } catch (e: Exception) {
            log.warn("[Guava Lock] Failed to release session lock: key={}", lockKey, e)
        }
    }, lockExecutor)

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(GuavaLockStrategy::class.java)
        private const val POLL_INTERVAL_MS = 100L
    }
}

package maple.expectation.infrastructure.executor

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import org.springframework.core.task.TaskRejectedException

/**
 * Executor wrapper that blocks on submit when the delegate queue is full.
 *
 * Catches rejection (RejectedExecutionException / TaskRejectedException) and retries
 * with a configurable backoff instead of either running on the caller thread
 * (CallerRunsPolicy) or dropping the task (AbortPolicy).
 *
 * @param delegate underlying executor (typically ThreadPoolTaskExecutor)
 * @param backoffNanos pause between retries (default 1ms)
 * @param isShutdown check to abort retry loop during shutdown
 */
class BlockingSubmitExecutor(
    private val delegate: Executor,
    private val backoffNanos: Long = 1_000_000L,
    private val isShutdown: () -> Boolean = { false },
) : Executor {

    val submitRetryCount = AtomicLong()
    val submitRetryWaitNs = AtomicLong()

    override fun execute(command: Runnable) {
        var waitStart = 0L
        var retried = false

        while (true) {
            try {
                delegate.execute(command)
                if (retried) {
                    submitRetryWaitNs.addAndGet(System.nanoTime() - waitStart)
                }
                return
            } catch (e: Exception) {
                if (e !is RejectedExecutionException && e !is TaskRejectedException) throw e
                if (isShutdown()) throw e

                if (!retried) {
                    retried = true
                    waitStart = System.nanoTime()
                }
                submitRetryCount.incrementAndGet()
                LockSupport.parkNanos(backoffNanos)
            }
        }
    }
}

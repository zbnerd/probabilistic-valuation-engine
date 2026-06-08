package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface BackpressureLimiter {
    suspend fun <T> withPermit(timeoutMs: Long, block: suspend () -> T): T
}

class DefaultBackpressureLimiter(
    private val permits: Int,
    private val component: String = "unknown",
) : BackpressureLimiter {
    private val sem = Semaphore(permits)

    override suspend fun <T> withPermit(timeoutMs: Long, block: suspend () -> T): T {
        if (!sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw BackpressureRejectedException(component, timeoutMs)
        }
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}

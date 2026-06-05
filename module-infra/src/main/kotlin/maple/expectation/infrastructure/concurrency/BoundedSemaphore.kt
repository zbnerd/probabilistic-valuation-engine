package maple.expectation.infrastructure.concurrency

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface BoundedSemaphore {
    suspend fun <T> withPermit(block: suspend () -> T): T
    fun availablePermits(): Int
}

class DefaultBoundedSemaphore(permits: Int) : BoundedSemaphore {
    private val sem = Semaphore(permits)

    override suspend fun <T> withPermit(block: suspend () -> T): T = sem.withPermit { block() }

    override fun availablePermits(): Int = sem.availablePermits
}

package maple.expectation.infrastructure.concurrency

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BoundedSemaphoreTest {
    @Test
    fun `max N concurrent blocks execute simultaneously`() = runTest {
        val sem = DefaultBoundedSemaphore(permits = 2)
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val jobs = (1..5).map {
            async {
                sem.withPermit {
                    val now = concurrent.incrementAndGet()
                    maxObserved.updateAndGet { kotlin.math.max(it, now) }
                    delay(50)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(2, maxObserved.get())
    }

    @Test
    fun `availablePermits reports remaining`() = runTest {
        val sem = DefaultBoundedSemaphore(permits = 3)
        assertEquals(3, sem.availablePermits())
        sem.withPermit { /* holds nothing */ }
        assertEquals(3, sem.availablePermits())
    }
}

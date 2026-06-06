package maple.expectation.infrastructure.concurrency

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.atomic.AtomicInteger

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

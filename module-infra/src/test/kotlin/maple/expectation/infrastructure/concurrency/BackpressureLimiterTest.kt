package maple.expectation.infrastructure.concurrency

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackpressureLimiterTest {

    @Test
    fun `withPermit returns block result on success`() = runTest {
        val limiter = DefaultBackpressureLimiter(permits = 1)
        val result = limiter.withPermit(timeoutMs = 1_000) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `withPermit throws BackpressureRejected when no permits available`() {
        val limiter = DefaultBackpressureLimiter(permits = 0, component = "test-component")
        assertThrows(BackpressureRejectedException::class.java) {
            kotlinx.coroutines.runBlocking { limiter.withPermit(50) { "never" } }
        }
    }

    @Test
    fun `exception message includes component and timeout`() {
        val limiter = DefaultBackpressureLimiter(permits = 0, component = "my-pipeline")
        val ex = assertThrows(BackpressureRejectedException::class.java) {
            kotlinx.coroutines.runBlocking { limiter.withPermit(100) { "never" } }
        }
        assertEquals("my-pipeline", ex.component)
        assert(ex.message!!.contains("100ms"))
        assert(ex.message!!.contains("my-pipeline"))
    }
}

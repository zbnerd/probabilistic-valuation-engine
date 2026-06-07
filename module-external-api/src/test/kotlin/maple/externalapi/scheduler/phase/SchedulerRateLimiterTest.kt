package maple.externalapi.scheduler.phase

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchedulerRateLimiterTest {

    private val rateLimiter = SchedulerRateLimiter()

    @Test
    fun `acquirePermitsSuspend returns permits when available`() = runTest {
        val bucket = rateLimiter.newRateLimiter(10)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 5, 10)
        assertThat(permits).isEqualTo(5)
    }

    @Test
    fun `acquirePermitsSuspend respects remaining limit`() = runTest {
        val bucket = rateLimiter.newRateLimiter(100)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 50, 10)
        assertThat(permits).isEqualTo(10)
    }

    @Test
    fun `acquirePermitsSuspend returns zero when bucket empty without blocking`() = runTest {
        val bucket = rateLimiter.newRateLimiter(1)
        rateLimiter.acquirePermitsSuspend(bucket, 1, 1)
        val permits = rateLimiter.acquirePermitsSuspend(bucket, 1, 1)
        assertThat(permits).isEqualTo(0)
    }
}

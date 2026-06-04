package maple.externalapi.scheduler.phase

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchedulerPhaseUtilsTest {

    @Test
    fun `acquirePermitsSuspend returns permits when available`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(10)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 5, 10)
        assertThat(permits).isEqualTo(5)
    }

    @Test
    fun `acquirePermitsSuspend respects remaining limit`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(100)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 50, 10)
        assertThat(permits).isEqualTo(10) // min(batchSize, remaining)
    }

    @Test
    fun `acquirePermitsSuspend returns zero when bucket empty without blocking`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(1)
        // Consume the only permit
        SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 1, 1)
        // Bucket is now empty — should return 0 after delay (not throw, not block thread)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 1, 1)
        assertThat(permits).isEqualTo(0)
    }
}

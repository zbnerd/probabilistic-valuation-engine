package maple.externalapi.scheduler.phase

import maple.externalapi.metrics.SnapshotFetchMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Duration
import java.time.Instant

class FetchProgressTrackerTest {

    private val fetchMetrics: SnapshotFetchMetrics = mock()
    private val start = Instant.now()
    private lateinit var tracker: FetchProgressTracker

    @BeforeEach
    fun setUp() {
        tracker = FetchProgressTracker(
            progress = BatchProgress(0, 0, 0, start),
            fetchMetrics = fetchMetrics,
            endpoint = "character-basic",
        )
    }

    @Test
    fun `recordSuccess increments successCount and leaves failCount zero`() {
        tracker.recordSuccess(ocid = "oc1", duration = Duration.ofMillis(100), queueDepth = 5)

        val state = tracker.snapshot()
        assertThat(state.successCount).isEqualTo(1)
        assertThat(state.failCount).isEqualTo(0)
        verify(fetchMetrics).recordFetchJoin("character-basic", Duration.ofMillis(100))
    }

    @Test
    fun `recordFailure increments failCount and leaves successCount zero`() {
        val ex = RuntimeException("API error")
        tracker.recordFailure(ocid = "oc1", ex = ex)

        val state = tracker.snapshot()
        assertThat(state.successCount).isEqualTo(0)
        assertThat(state.failCount).isEqualTo(1)
    }

    @Test
    fun `snapshot returns BatchProgress preserving start instant`() {
        val snapshot = tracker.snapshot()
        assertThat(snapshot.start).isEqualTo(start)
    }

    @Test
    fun `multiple recordSuccess calls accumulate count`() {
        tracker.recordSuccess("oc1", Duration.ofMillis(50), 1)
        tracker.recordSuccess("oc2", Duration.ofMillis(60), 1)
        tracker.recordSuccess("oc3", Duration.ofMillis(70), 1)

        assertThat(tracker.snapshot().successCount).isEqualTo(3)
    }
}

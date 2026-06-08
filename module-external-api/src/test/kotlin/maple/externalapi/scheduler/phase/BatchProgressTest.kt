package maple.externalapi.scheduler.phase

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BatchProgressTest {

    @Test
    fun `defaults to zero counters and current instant`() {
        val before = Instant.now()
        val progress = BatchProgress()
        val after = Instant.now()

        assertThat(progress.successCount).isZero()
        assertThat(progress.failCount).isZero()
        assertThat(progress.lastProgressLog).isZero()
        assertThat(progress.start).isBetween(before, after)
    }

    @Test
    fun `totalProcessed sums success and fail`() {
        val progress = BatchProgress(successCount = 7, failCount = 3)

        assertThat(progress.totalProcessed()).isEqualTo(10)
    }

    @Test
    fun `shouldLogProgress false when delta below interval`() {
        val progress = BatchProgress(successCount = 100, lastProgressLog = 0)

        assertThat(progress.shouldLogProgress(logInterval = 5_000)).isFalse()
    }

    @Test
    fun `shouldLogProgress true when delta hits interval`() {
        val progress = BatchProgress(successCount = 5_000, lastProgressLog = 0)

        assertThat(progress.shouldLogProgress(logInterval = 5_000)).isTrue()
    }

    @Test
    fun `markLogged updates lastProgressLog to current total`() {
        val progress = BatchProgress(successCount = 5_000, failCount = 0, lastProgressLog = 0)

        val marked = progress.markLogged()

        assertThat(marked.lastProgressLog).isEqualTo(5_000)
        assertThat(marked.successCount).isEqualTo(5_000)
    }

    @Test
    fun `addSuccess and addFailure produce new instance with updated counters`() {
        val progress = BatchProgress()

        val updated = progress.addSuccess(3).addFailure(1)

        assertThat(updated.successCount).isEqualTo(3)
        assertThat(updated.failCount).isEqualTo(1)
        assertThat(progress.successCount).isZero() // original unchanged
    }
}

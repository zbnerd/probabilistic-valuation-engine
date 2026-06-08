package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class SchedulerRateLimiter {
    fun newRateLimiter(permits: Int): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(permits.toLong())
                .refillIntervally(permits.toLong(), Duration.ofSeconds(1))
                .build(),
        )
        .build()

    suspend fun acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int {
        val maxBatch = minOf(batchSize, remaining)
        val consumed = rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()
        if (consumed == 0) {
            delay(PHASE_TICK_INTERVAL_MS)
        }
        return consumed
    }
    companion object {
        private const val PHASE_TICK_INTERVAL_MS: Long = 100L
    }
}

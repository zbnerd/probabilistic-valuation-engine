package maple.pipeline.messaging.policy

import java.time.Duration

data class DeliveryRetryPolicy(
    val maxRetries: Int = 3,
    val backoff: Duration = Duration.ofSeconds(1),
) {
    init {
        require(maxRetries == 3) { "initial migration preserves exactly three retries" }
        require(backoff == Duration.ofSeconds(1)) { "initial migration preserves one-second backoff" }
    }
}

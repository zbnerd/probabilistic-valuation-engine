package maple.expectation.infrastructure.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "admission-control")
data class GlobalAdmissionProperties(
    /**
     * Maximum concurrent cold-path calculations
     *
     * 🔥 TUNED: 100 (balanced for I/O-bound workload with Nexon API calls)
     *
     * Service is NOT CPU-bound (waits for Nexon API, DB), so higher concurrency OK
     *
     * Formula for I/O-bound: cores × 10~20
     * - 8 cores × 12.5 = 100 ← CURRENT
     */
    @DefaultValue("100") @Min(10) @Max(500)
    val maxInFlight: Int = 100,

    /**
     * Maximum wait time in queue (ms)
     */
    @DefaultValue("5000") @Min(1000) @Max(30000)
    val queueTimeoutMs: Long = 5000,

    /**
     * Maximum queue size (REAL BOUNDED QUEUE)
     */
    @DefaultValue("1000") @Min(100) @Max(10000)
    val maxQueueSize: Int = 1000,

    /**
     * Worker pool size for queue consumption
     * Worker threads consume from queue, preventing HTTP thread blocking
     */
    @DefaultValue("16") @Min(4) @Max(64)
    val workerPoolSize: Int = 16,
) {
    init {
        require(maxQueueSize >= maxInFlight * 2) {
            "maxQueueSize ($maxQueueSize) must be at least 2x maxInFlight ($maxInFlight) for proper operation"
        }
    }
}

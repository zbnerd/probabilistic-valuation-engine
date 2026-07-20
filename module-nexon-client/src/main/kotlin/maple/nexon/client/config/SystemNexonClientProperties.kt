package maple.nexon.client.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "nexon.http-client")
data class SystemNexonClientProperties(
    @field:NotBlank
    override val poolName: String = "nexon-pool",
    @field:Min(1)
    @field:Max(10_000)
    override val maxConnections: Int = 250,
    @field:Min(1)
    @field:Max(1_000_000)
    override val pendingAcquireMaxCount: Int = 1_000,
    @field:Min(1)
    @field:Max(120_000)
    override val pendingAcquireTimeoutMs: Long = 5_000,
    @field:Min(1)
    @field:Max(60_000)
    override val connectTimeoutMs: Int = 3_000,
    @field:Min(1)
    @field:Max(120)
    override val responseTimeoutSeconds: Long = 5,
    @field:Min(1)
    @field:Max(120)
    override val callTimeoutSeconds: Long = 10,
    @field:Min(1_024)
    @field:Max(16 * 1024 * 1024)
    override val maxInMemorySizeBytes: Int = 2 * 1024 * 1024,
    override val metricsEnabled: Boolean = true,
) : NexonHttpClientProperties {
    fun validated(): SystemNexonClientProperties = apply { validateValues() }
}

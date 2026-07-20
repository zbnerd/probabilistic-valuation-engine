package maple.nexon.client.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "nexon.byok-http-client")
data class ByokNexonClientProperties(
    @field:NotBlank
    override val poolName: String = "nexon-byok-pool",
    @field:Min(1)
    @field:Max(10_000)
    override val maxConnections: Int = 32,
    @field:Min(1)
    @field:Max(1_000_000)
    override val pendingAcquireMaxCount: Int = 128,
    @field:Min(1)
    @field:Max(120_000)
    override val pendingAcquireTimeoutMs: Long = 2_000,
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
    override val maxInMemorySizeBytes: Int = 256 * 1024,
    override val metricsEnabled: Boolean = true,
) : NexonHttpClientProperties {
    fun validated(): ByokNexonClientProperties = apply { validateValues() }
}

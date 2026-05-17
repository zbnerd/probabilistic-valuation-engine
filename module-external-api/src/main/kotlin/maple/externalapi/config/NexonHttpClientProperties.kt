package maple.externalapi.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "nexon.http-client")
data class NexonHttpClientProperties(
    val poolName: String = "nexon-pool",
    val maxConnections: Int = 50,
    val pendingAcquireMaxCount: Int = 1000,
    val pendingAcquireTimeoutMs: Long = 5000,
    val connectTimeoutMs: Int = 3000,
    val responseTimeoutSeconds: Long = 5,
    val maxInMemorySizeBytes: Int = 2 * 1024 * 1024,
    val metricsEnabled: Boolean = true,
)

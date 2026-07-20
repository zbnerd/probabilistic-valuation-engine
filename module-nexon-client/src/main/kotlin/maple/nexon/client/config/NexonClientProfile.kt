package maple.nexon.client.config

import java.time.Duration

enum class NexonClientProfile {
    SYSTEM_BULK,
    USER_BYOK,
    ;

    companion object {
        fun validateDistinctPoolNames(
            system: SystemNexonClientProperties,
            byok: ByokNexonClientProperties,
        ) {
            require(system.poolName != byok.poolName) { "Nexon system and BYOK pool names must differ" }
        }
    }
}

interface NexonHttpClientProperties {
    val poolName: String
    val maxConnections: Int
    val pendingAcquireMaxCount: Int
    val pendingAcquireTimeoutMs: Long
    val connectTimeoutMs: Int
    val responseTimeoutSeconds: Long
    val callTimeoutSeconds: Long
    val maxInMemorySizeBytes: Int
    val metricsEnabled: Boolean

    fun validateValues() {
        require(SAFE_POOL_NAME.matches(poolName)) { "Nexon pool name must be a bounded static identifier" }
        require(maxConnections in 1..10_000) { "Nexon max connections out of bounds" }
        require(pendingAcquireMaxCount in 1..1_000_000) { "Nexon pending acquire count out of bounds" }
        require(pendingAcquireTimeoutMs in 1..120_000) { "Nexon pending acquire timeout out of bounds" }
        require(connectTimeoutMs in 1..60_000) { "Nexon connect timeout out of bounds" }
        require(responseTimeoutSeconds in 1..120) { "Nexon response timeout out of bounds" }
        require(callTimeoutSeconds in 1..120) { "Nexon call timeout out of bounds" }
        require(maxInMemorySizeBytes in 1_024..MAX_BODY_BYTES) { "Nexon response body cap out of bounds" }
    }

    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
        private val SAFE_POOL_NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

data class NexonTransportSettings(
    val profile: NexonClientProfile,
    val poolName: String,
    val maxConnections: Int,
    val pendingAcquireMaxCount: Int,
    val pendingAcquireTimeout: Duration,
    val connectTimeoutMs: Int,
    val responseTimeout: Duration,
    val callTimeout: Duration,
    val maxInMemorySizeBytes: Int,
    val metricsEnabled: Boolean,
)

internal fun NexonHttpClientProperties.toSettings(profile: NexonClientProfile): NexonTransportSettings {
    validateValues()
    return NexonTransportSettings(
        profile = profile,
        poolName = poolName,
        maxConnections = maxConnections,
        pendingAcquireMaxCount = pendingAcquireMaxCount,
        pendingAcquireTimeout = Duration.ofMillis(pendingAcquireTimeoutMs),
        connectTimeoutMs = connectTimeoutMs,
        responseTimeout = Duration.ofSeconds(responseTimeoutSeconds),
        callTimeout = Duration.ofSeconds(callTimeoutSeconds),
        maxInMemorySizeBytes = maxInMemorySizeBytes,
        metricsEnabled = metricsEnabled,
    )
}

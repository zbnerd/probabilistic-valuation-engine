package maple.restcontroller.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "expectation.v6")
class V6ReadProperties {
    var enabled: Boolean = false
    var batchWindowMs: Long = 10
    var requestTimeoutMs: Long = 1500
    var maxBatchSize: Int = 200
    var queueCapacity: Int = 5000
    var shutdownDrainTimeoutSeconds: Long = 5
    var cacheTtlSeconds: Long = 300
    var urgentPendingTtlSeconds: Long = 30
    var statusRetryAfterSeconds: Long = 3
    var statusEstimatedThroughputPerSecond: Double = 5.0
}

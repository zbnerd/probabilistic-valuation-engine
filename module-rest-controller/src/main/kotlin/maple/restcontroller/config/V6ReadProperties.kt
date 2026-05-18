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
    var ranking: Ranking = Ranking()
    var popular: Popular = Popular()

    class Ranking {
        var redisKeyPrefix: String = "ranking:equipment:total-cost"
        var topSize: Int = 10
    }

    class Popular {
        var redisKeyPrefix: String = "popular:characters:v6"
        var topSize: Int = 10
        var defaultWindowHours: Int = 3
        var maxWindowHours: Int = 24
        var bucketTtlHours: Long = 48
        var rollingTtlSeconds: Long = 60
    }
}

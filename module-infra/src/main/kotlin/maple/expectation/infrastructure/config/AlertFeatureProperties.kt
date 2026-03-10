package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Alert Feature Flags Configuration
 *
 * <p>Feature flags for gradual rollout of stateless alert system:
 *
 * <ul>
 *   <li>alert.stateless.enabled: Master switch for stateless alert system (default: true)
 *   <li>alert.stateless.fallback-to-file: Enable file fallback when Discord fails (default: true)
 *   <li>alert.in-memory.capacity: Maximum alerts stored in memory before eviction (default: 1000)
 * </ul>
 *
 * @see maple.expectation.alert.DiscordAlertChannel
 * @see maple.expectation.config.AlertWebClientConfig
 */
@Component
@ConfigurationProperties(prefix = "alert")
class AlertFeatureProperties {

    /** Stateless alert system configuration */
    var stateless: Stateless = Stateless()

    /** In-memory buffer capacity for alert queue. Alerts exceeding this capacity will be evicted using LRU policy. */
    var inMemory: InMemory = InMemory()

    /** File-based alert persistence configuration. Used as fallback when Discord API is unavailable. */
    var file: File = File()

    /** Stateless alert system configuration */
    class Stateless {
        /** Master switch for stateless alert system. Default: true (enabled) */
        var enabled: Boolean = true

        /** Enable fallback to file logging when Discord API fails. Default: true (enabled) */
        var fallbackToFile: Boolean = true
    }

    /** In-memory buffer configuration */
    class InMemory {
        /** Maximum capacity of in-memory alert buffer. Default: 1000 alerts */
        var capacity: Int = 1000
    }

    /** File-based alert persistence configuration */
    class File {
        /** Path to file-based alert log. Default: /var/log/maple-alerts.log */
        var path: String = "/var/log/maple-alerts.log"
    }
}

package maple.expectation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Centralized timeout configuration.
 *
 * Supports OCP (Open/Closed Principle) by eliminating scattered hard-coded timeout values.
 * Timeouts can be adjusted via configuration without code changes.
 */
@Component
@ConfigurationProperties(prefix = "timeouts")
class TimeoutProperties {
    /** Equipment leader/follower computation timeout */
    var equipment: Duration = Duration.ofSeconds(30)

    /** Nexon API call timeout */
    var apiCall: Duration = Duration.ofSeconds(10)

    /** Async operation timeout */
    var async: Duration = Duration.ofSeconds(30)

    /** Database query timeout */
    var database: Duration = Duration.ofSeconds(5)

    /** Cache operation timeout */
    var cache: Duration = Duration.ofSeconds(2)
}

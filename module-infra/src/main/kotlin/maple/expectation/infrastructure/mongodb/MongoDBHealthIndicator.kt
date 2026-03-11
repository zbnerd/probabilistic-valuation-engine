package maple.expectation.infrastructure.mongodb

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * V5 CQRS: MongoDB Health Check Indicator
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Expose MongoDB connection health via Actuator
 *   <li>Check connectivity and index status
 *   <li>Integrated with /actuator/health endpoint
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * Only active when v5.enabled=true
 */
@Component
@ConditionalOnBean(MongoDBConfig::class)
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class MongoDBHealthIndicator(
    private val mongoConfig: MongoDBConfig,
) : HealthIndicator {

    override fun health(): Health {
        val isHealthy = mongoConfig.isHealthy()

        return if (isHealthy) {
            Health.up()
                .withDetail("database", "maple_expectation_v5")
                .withDetail("ttl_enabled", "24 hours")
                .withDetail("status", "connected")
                .build()
        } else {
            Health.down()
                .withDetail("database", "maple_expectation_v5")
                .withDetail("status", "disconnected")
                .build()
        }
    }
}

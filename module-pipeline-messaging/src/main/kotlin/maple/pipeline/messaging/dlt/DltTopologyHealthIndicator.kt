package maple.pipeline.messaging.dlt

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

class DltTopologyHealthIndicator(
    private val resources: DltTopologyResources,
) : HealthIndicator {
    override fun health(): Health {
        if (resources.subscriptionCount == 0) {
            return Health.up().withDetail(SUBSCRIPTIONS, 0).build()
        }
        val status = resources.lastStatus()
            ?: return Health.outOfService().withDetail(SUBSCRIPTIONS, resources.subscriptionCount).build()
        val builder = if (status.healthy) Health.up() else Health.down()
        return builder
            .withDetail(SUBSCRIPTIONS, status.subscriptions)
            .withDetail("sourcePartitions", status.sourcePartitions)
            .withDetail("dltPartitions", status.dltPartitions)
            .withDetail("missingSources", status.missingSources)
            .withDetail("pendingActions", status.actions.size)
            .withDetail("failureCategory", status.failureCategory ?: "NONE")
            .build()
    }

    private companion object {
        private const val SUBSCRIPTIONS = "subscriptions"
    }
}

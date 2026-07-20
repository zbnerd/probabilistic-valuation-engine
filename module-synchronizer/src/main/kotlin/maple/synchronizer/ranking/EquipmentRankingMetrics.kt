package maple.synchronizer.ranking

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class EquipmentRankingMetrics(
    registry: MeterRegistry,
) {
    private val failureCounters: Map<String, Counter> = FAILURE_STAGES.associateWith { stage ->
        registry.counter("equipment_ranking_write_failures_total", "stage", stage)
    }

    fun recordFailure(stage: String) {
        require(stage in FAILURE_STAGES) { "Unsupported equipment ranking stage: $stage" }
        failureCounters.getValue(stage).increment()
    }

    private companion object {
        private val FAILURE_STAGES = setOf("filter", "group", "redis")
    }
}

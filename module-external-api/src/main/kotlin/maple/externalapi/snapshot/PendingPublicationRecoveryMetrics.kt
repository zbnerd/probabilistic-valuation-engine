package maple.externalapi.snapshot

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class PendingPublicationRecoveryMetrics(registry: MeterRegistry) {
    private val listFailures = failureCounter(registry, LIST_STAGE)
    private val replayFailures = failureCounter(registry, REPLAY_STAGE)
    private val recoveredEndpoints = Counter.builder("artifact_publication_recovered_endpoints_total")
        .description("Publication-pending artifact endpoints recovered at startup")
        .register(registry)

    fun recordListFailure() = listFailures.increment()

    fun recordReplayFailure() = replayFailures.increment()

    fun recordRecoveredEndpoint() = recoveredEndpoints.increment()

    private fun failureCounter(registry: MeterRegistry, stage: String): Counter =
        Counter.builder("artifact_publication_recovery_failures_total")
            .description("Artifact publication recovery failures by static recovery stage")
            .tag("stage", stage)
            .register(registry)

    private companion object {
        const val LIST_STAGE: String = "list"
        const val REPLAY_STAGE: String = "replay"
    }
}

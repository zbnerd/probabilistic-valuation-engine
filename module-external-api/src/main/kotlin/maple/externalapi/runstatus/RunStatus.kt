package maple.externalapi.runstatus

import java.time.Instant

data class RunStatus(
    val runId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val chunksProcessed: Int = 0,
    val recordsProcessed: Long = 0,
    val errorMessage: String? = null,
) {
    val isTerminal: Boolean get() = phase == PipelinePhase.COMPLETED || phase == PipelinePhase.FAILED
}

package maple.externalapi.runstatus

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class RunStatus(
    val runId: String,
    val phase: PipelinePhase,
    val triggeredPhase: PipelinePhase,
    val startedAt: Instant,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
    val chunksProcessed: Int = 0,
    val recordsProcessed: Long = 0,
    val errorMessage: String? = null,
) {
    @get:JsonProperty("terminal")
    val isTerminal: Boolean get() = phase == PipelinePhase.COMPLETED || phase == PipelinePhase.FAILED
}

package maple.externalapi.runstatus

import java.time.Instant

/**
 * Per-phase run-status payload. Active slots and last-completed run per phase.
 * The `current` and `lastCompleted` fields are legacy aliases for
 * single-slot API consumers; deprecated.
 */
data class RunStatusResponse(
    val slots: Map<PipelinePhase, RunStatus?>,
    val lastCompletedByPhase: Map<PipelinePhase, RunStatus?>,
    @Deprecated("Use slots map instead") val current: RunStatus?,
    @Deprecated("Use lastCompletedByPhase map instead") val lastCompleted: RunStatus?,
    val loopSummaries: Map<String, LoopSummaryView> = emptyMap(),
)

/**
 * Per-active-loop summary. Keyed by phase.name in the parent
 * RunStatusResponse.loopSummaries map.
 */
data class LoopSummaryView(
    val loopId: String,
    val phase: String,
    val startedAt: Instant,
    val iterationCount: Int,
    val lastRunId: String?,
    val status: String, // RUNNING | STOPPING | STOPPED
    val lastError: String?,
)

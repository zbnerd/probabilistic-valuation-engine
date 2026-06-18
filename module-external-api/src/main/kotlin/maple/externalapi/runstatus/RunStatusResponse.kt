package maple.externalapi.runstatus

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
)

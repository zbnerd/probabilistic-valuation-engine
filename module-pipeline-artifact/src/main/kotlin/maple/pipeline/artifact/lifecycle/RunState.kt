package maple.pipeline.artifact.lifecycle

sealed interface RunState {
    data object Absent : RunState
    data object Running : RunState
    data object ArtifactSucceededPublicationPending : RunState
    data object Published : RunState
    data object PublishedWithOrphanMarker : RunState
    data class Incomplete(val reason: String) : RunState
    data class Invalid(val reason: String) : RunState
}

package maple.pipeline.artifact.retention

import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.lifecycle.RunState

data class ArtifactEndpointInfo(
    val endpoint: String,
    val manifestKey: ArtifactKey?,
    val state: RunState,
)

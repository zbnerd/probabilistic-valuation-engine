package maple.pipeline.artifact.retention

import java.time.Instant
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.lifecycle.RunState

data class ArtifactRunInfo(
    val runId: String,
    val prefix: ArtifactKey,
    val createdAt: Instant,
    val sizeBytes: Long,
    val state: RunState,
    val endpoints: List<ArtifactEndpointInfo>,
)

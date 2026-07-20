package maple.synchronizer.event

import maple.pipeline.artifact.identity.SourceArtifactLayout
import org.springframework.stereotype.Component

@Component
class ResultChunkEventPathBuilder {
    fun sourceObjectKey(runId: String, sourceEndpoint: String, chunkId: String): String =
        SourceArtifactLayout.chunk(runId, sourceEndpoint, chunkId).value
}

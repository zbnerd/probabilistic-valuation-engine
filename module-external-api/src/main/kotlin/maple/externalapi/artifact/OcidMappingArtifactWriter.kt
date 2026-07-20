package maple.externalapi.artifact

import maple.pipeline.artifact.identity.OcidMappingArtifactLayout
import maple.pipeline.artifact.write.ArtifactWriter
import maple.pipeline.artifact.write.GzipArtifactSession
import org.springframework.stereotype.Component

@Component
class OcidMappingArtifactWriter(
    private val artifactWriter: ArtifactWriter,
) {
    fun open(runId: String): GzipArtifactSession = artifactWriter.openGzip(
        OcidMappingArtifactLayout.mapping(runId),
    )
}

package maple.pipeline.artifact.identity

object OcidMappingArtifactLayout {
    fun mapping(runId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        return ArtifactKey.require("ocid-mapping/ocid-mapping-${validatedRunId.value}.jsonl.gz")
    }

    fun parquetSidecar(runId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        return ArtifactKey.require("ocid-mapping-parquet/ocid-mapping-${validatedRunId.value}.parquet")
    }
}

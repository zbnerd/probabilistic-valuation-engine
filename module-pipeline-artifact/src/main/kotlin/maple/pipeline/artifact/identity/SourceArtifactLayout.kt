package maple.pipeline.artifact.identity

object SourceArtifactLayout {
    val runPrefix: ArtifactPrefix = ArtifactKey.require(SOURCE_ROOT).asPrefix()

    fun runRoot(runId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        return ArtifactKey.require("$SOURCE_ROOT/${validatedRunId.value}")
    }

    fun endpointRoot(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require("$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}")
    }

    fun chunksRoot(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require(
            "$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/chunks",
        )
    }

    fun chunk(runId: String, endpoint: String, chunkId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        val validatedChunkId = ArtifactSegment.require(chunkId)
        return ArtifactKey.require(
            "${chunksRoot(validatedRunId.value, validatedEndpoint.value).value}/${validatedChunkId.value}.jsonl.gz",
        )
    }

    fun manifest(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require(
            "$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/manifest.json",
        )
    }

    fun failedRecords(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require(
            "$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/failed.jsonl",
        )
    }

    fun legacyRankingRunning(runId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        return ArtifactKey.require("$SOURCE_ROOT/${validatedRunId.value}/_RUNNING")
    }

    fun endpointRunning(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require(
            "$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/_RUNNING",
        )
    }

    fun endpointSuccess(runId: String, endpoint: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        return ArtifactKey.require(
            "$SOURCE_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/_SUCCESS",
        )
    }
}

private const val SOURCE_ROOT: String = "runs"

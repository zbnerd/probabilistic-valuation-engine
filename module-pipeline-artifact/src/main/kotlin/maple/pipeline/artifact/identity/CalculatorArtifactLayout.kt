package maple.pipeline.artifact.identity

object CalculatorArtifactLayout {
    val runPrefix: ArtifactPrefix = ArtifactKey.require(CALCULATOR_RUN_ROOT).asPrefix()

    fun runRoot(runId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        return ArtifactKey.require("$CALCULATOR_RUN_ROOT/${validatedRunId.value}")
    }

    fun resultChunk(runId: String, endpoint: String, chunkId: String): ArtifactKey {
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        val validatedChunkId = ArtifactSegment.require(chunkId)
        return ArtifactKey.require(
            "$CALCULATOR_RUN_ROOT/${validatedRunId.value}/${validatedEndpoint.value}/" +
                "chunks/result-${validatedChunkId.value}.jsonl.gz",
        )
    }
}

private const val CALCULATOR_RUN_ROOT: String = "calculator/runs"

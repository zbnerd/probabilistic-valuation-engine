package maple.pipeline.artifact.identity

object CleanupInboxLayout {
    val prefix: ArtifactPrefix = ArtifactKey.require(CLEANUP_INBOX_ROOT).asPrefix()

    fun entry(eventId: String): ArtifactKey {
        val validatedEventId = ArtifactSegment.require(eventId)
        return ArtifactKey.require("$CLEANUP_INBOX_ROOT/${validatedEventId.value}.json")
    }
}

private const val CLEANUP_INBOX_ROOT: String = "cleanup/inbox"

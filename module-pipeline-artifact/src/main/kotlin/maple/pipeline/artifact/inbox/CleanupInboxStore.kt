package maple.pipeline.artifact.inbox

import java.util.concurrent.CompletionStage
import maple.pipeline.artifact.identity.ArtifactKey

interface CleanupInboxStore {
    fun putIfAbsent(entry: CleanupInboxEntry): CompletionStage<InboxPutResult>

    fun listPage(afterKey: ArtifactKey?, limit: Int): CleanupInboxPage

    fun delete(key: ArtifactKey)

    fun pendingCount(): Long
}

data class CleanupInboxPage(
    val entries: List<Pair<ArtifactKey, CleanupInboxEntry>>,
    val nextAfterKey: ArtifactKey?,
)

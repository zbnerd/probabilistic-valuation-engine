package maple.pipeline.artifact.inbox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletionStage
import maple.expectation.common.event.ChunkConsumedEvent
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.CleanupInboxLayout
import maple.pipeline.artifact.storage.ConditionalObjectStorage
import maple.pipeline.artifact.storage.PutIfAbsentResult

class ObjectStorageCleanupInboxStore(
    private val objectStorage: ConditionalObjectStorage,
    private val objectMapper: ObjectMapper,
) : CleanupInboxStore {
    override fun putIfAbsent(entry: CleanupInboxEntry): CompletionStage<InboxPutResult> {
        val key = CleanupInboxLayout.entry(entry.eventId)
        require(entry.eventId == entry.event.eventId) {
            "cleanup inbox envelope eventId must match event eventId"
        }
        val envelopeBytes = objectMapper.writeValueAsBytes(entry)
        val incomingEvent = canonicalEvent(entry.event)
        return objectStorage.putIfAbsent(key.value, envelopeBytes).thenApply { result ->
            classifyPut(result, incomingEvent, entry.eventId)
        }
    }

    override fun listPage(afterKey: ArtifactKey?, limit: Int): CleanupInboxPage {
        val page = objectStorage.listPage(CleanupInboxLayout.prefix, afterKey, limit)
        val entries = page.objects.map { objectInfo ->
            val key = requireInboxEntryKey(ArtifactKey.require(objectInfo.key))
            key to read(key)
        }
        return CleanupInboxPage(entries, page.nextAfterKey)
    }

    override fun delete(key: ArtifactKey) {
        objectStorage.delete(requireInboxEntryKey(key).value)
    }

    override fun pendingCount(): Long {
        var count = 0L
        var afterKey: ArtifactKey? = null
        do {
            val page = objectStorage.listPage(CleanupInboxLayout.prefix, afterKey, PAGE_LIMIT)
            count += page.objects.size
            afterKey = page.nextAfterKey
        } while (afterKey != null)
        return count
    }

    private fun classifyPut(
        result: PutIfAbsentResult,
        incomingEvent: JsonNode,
        eventId: String,
    ): InboxPutResult = when (result) {
        is PutIfAbsentResult.Created -> InboxPutResult.Created
        is PutIfAbsentResult.Existing -> {
            if (storedCanonicalEvent(result.bytes) == incomingEvent) {
                InboxPutResult.Replay
            } else {
                InboxPutResult.IntegrityConflict(eventId)
            }
        }
    }

    private fun canonicalEvent(event: ChunkConsumedEvent): JsonNode =
        objectMapper.readTree(objectMapper.writeValueAsBytes(event))

    private fun storedCanonicalEvent(envelopeBytes: ByteArray): JsonNode {
        val envelope = objectMapper.readTree(envelopeBytes)
        return requireNotNull(envelope.get(EVENT_FIELD)) {
            "cleanup inbox envelope is missing semantic event payload"
        }
    }

    private fun read(key: ArtifactKey): CleanupInboxEntry =
        objectMapper.readValue(objectStorage.get(key.value), CleanupInboxEntry::class.java)

    private fun requireInboxEntryKey(key: ArtifactKey): ArtifactKey {
        require(key.value.startsWith(CleanupInboxLayout.prefix.value)) {
            "cleanup inbox key must be a descendant of '${CleanupInboxLayout.prefix.value}'"
        }
        val filename = key.value.removePrefix(CleanupInboxLayout.prefix.value)
        require('/' !in filename && filename.endsWith(JSON_SUFFIX)) {
            "cleanup inbox key must identify one JSON entry"
        }
        val eventId = filename.removeSuffix(JSON_SUFFIX)
        require(CleanupInboxLayout.entry(eventId) == key) {
            "cleanup inbox key does not match the canonical entry layout"
        }
        return key
    }

    private companion object {
        const val EVENT_FIELD: String = "event"
        const val JSON_SUFFIX: String = ".json"
        const val PAGE_LIMIT: Int = 1_000
    }
}

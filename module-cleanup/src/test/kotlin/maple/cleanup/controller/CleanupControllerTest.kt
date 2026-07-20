package maple.cleanup.controller

import java.time.Instant
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.cleanup.service.StaleKafkaSkipService
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.inbox.CleanupInboxEntry
import maple.pipeline.artifact.inbox.CleanupInboxPage
import maple.pipeline.artifact.inbox.CleanupInboxStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CleanupControllerTest {
    private val storage = mock<ObjectStorage>()
    private val runCleanupService = mock<RunCleanupService>()
    private val inboxStore = mock<CleanupInboxStore>()
    private val staleKafkaSkipService = mock<StaleKafkaSkipService>()

    @Test
    fun `cleanupInbox pages by last scanned key and completes durable entries`() {
        val first = entry("e1", "runs/one.json", null)
        val second = entry("e2", "runs/two.json", "runs/two-source.json")
        whenever(inboxStore.listPage(anyOrNull(), eq(100))).thenReturn(
            CleanupInboxPage(listOf(first), null),
            CleanupInboxPage(listOf(second), null),
            CleanupInboxPage(emptyList(), null),
        )
        val controller = controller()

        val response = requireNotNull(controller.cleanupInbox().body)

        assertThat(response).isEqualTo(
            InboxCleanupResponse(scanned = 2, completed = 2, retainedForRetry = 0, deletedTargets = 3),
        )
        verify(inboxStore).listPage(null, 100)
        verify(inboxStore).listPage(first.first, 100)
        verify(inboxStore).listPage(second.first, 100)
        verify(inboxStore).delete(first.first)
        verify(inboxStore).delete(second.first)
    }

    @Test
    fun `partial target failure retains entry and continues to the next key`() {
        val failed = entry("e1", "runs/fail.json", "runs/fail-source.json")
        val completed = entry("e2", "runs/good.json", null)
        whenever(inboxStore.listPage(anyOrNull(), eq(100))).thenReturn(
            CleanupInboxPage(listOf(failed, completed), null),
            CleanupInboxPage(emptyList(), null),
        )
        doThrow(IllegalStateException("storage down")).whenever(storage).delete("runs/fail.json")
        val controller = controller()

        val response = requireNotNull(controller.cleanupInbox().body)

        assertThat(response).isEqualTo(
            InboxCleanupResponse(scanned = 2, completed = 1, retainedForRetry = 1, deletedTargets = 2),
        )
        verify(storage).delete("runs/fail-source.json")
        verify(storage).delete("runs/good.json")
        verify(inboxStore, never()).delete(failed.first)
        verify(inboxStore).delete(completed.first)
    }

    @Test
    fun `cleanupInbox stops at per-request cap`() {
        val first = entry("e1", "runs/one.json", null)
        val second = entry("e2", "runs/two.json", null)
        whenever(inboxStore.listPage(anyOrNull(), eq(1))).thenReturn(
            CleanupInboxPage(listOf(first), null),
            CleanupInboxPage(listOf(second), null),
        )
        val controller = controller(InboxProperties(drainPageSize = 1, maxDrainEntriesPerRequest = 2))

        val response = requireNotNull(controller.cleanupInbox().body)

        assertThat(response.scanned).isEqualTo(2)
        assertThat(response.completed).isEqualTo(2)
        verify(inboxStore).listPage(null, 1)
        verify(inboxStore).listPage(first.first, 1)
        verify(inboxStore, never()).listPage(second.first, 1)
    }

    @Test
    fun `cleanupInbox returns zeros when durable inbox is empty`() {
        whenever(inboxStore.listPage(anyOrNull(), any())).thenReturn(CleanupInboxPage(emptyList(), null))

        val response = requireNotNull(controller().cleanupInbox().body)

        assertThat(response).isEqualTo(
            InboxCleanupResponse(scanned = 0, completed = 0, retainedForRetry = 0, deletedTargets = 0),
        )
    }

    @Test
    fun `cleanupRuns delegates to RunCleanupService and returns result`() {
        whenever(runCleanupService.cleanupRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 3, bytesDeleted = 1024L, errors = 0, throttled = 0),
        )

        val response = requireNotNull(controller().cleanupRuns().body)

        assertThat(response.runsDeleted).isEqualTo(3)
        assertThat(response.bytesDeleted).isEqualTo(1024L)
    }

    @Test
    fun `cleanupCalculatorRuns delegates to RunCleanupService and returns result`() {
        whenever(runCleanupService.cleanupCalculatorRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 5, bytesDeleted = 2048L, errors = 0, throttled = 0),
        )

        val response = requireNotNull(controller().cleanupCalculatorRuns().body)

        assertThat(response.runsDeleted).isEqualTo(5)
        assertThat(response.bytesDeleted).isEqualTo(2048L)
    }

    private fun controller(properties: InboxProperties = InboxProperties()): CleanupController = CleanupController(
        runCleanupService = runCleanupService,
        inboxStore = inboxStore,
        inboxProperties = properties,
        objectStorage = storage,
        staleKafkaSkipService = staleKafkaSkipService,
    )

    private fun entry(
        eventId: String,
        objectKey: String,
        sourceObjectKey: String?,
    ): Pair<ArtifactKey, CleanupInboxEntry> {
        val event = ChunkConsumedEvent(
            eventId = eventId,
            runId = "run-$eventId",
            endpoint = "basic",
            chunkId = "chunk-$eventId",
            objectKey = objectKey,
            sourceObjectKey = sourceObjectKey,
            consumedAt = Instant.EPOCH,
        )
        return ArtifactKey.require("cleanup/inbox/$eventId.json") to CleanupInboxEntry(
            eventId = eventId,
            topic = "synchronizer.chunk.consumed",
            partition = 0,
            offset = 1,
            receivedAt = Instant.EPOCH,
            event = event,
        )
    }
}

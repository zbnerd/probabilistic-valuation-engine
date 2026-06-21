package maple.cleanup.controller

import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.cleanup.service.StaleKafkaSkipService
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.common.storage.ObjectStorage
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate

class CleanupControllerTest {
    private val storage: ObjectStorage = mock()
    private val runCleanupService: RunCleanupService = mock()
    private val inbox: ConsumedChunkInbox = mock()
    private val staleKafkaSkipService: StaleKafkaSkipService = mock()
    private val inboxProperties = InboxProperties()

    @Test
    fun `cleanupInbox deletes each event objectKey via ObjectStorage and counts failures`() {
        val events = listOf(
            ChunkConsumedEvent(
                runId = "r1",
                endpoint = "basic",
                chunkId = "c1",
                objectKey = "runs/abc/manifest.json",
                sourceObjectKey = null,
            ),
            ChunkConsumedEvent(
                runId = "r2",
                endpoint = "basic",
                chunkId = "c2",
                objectKey = "runs/def/manifest.json",
                sourceObjectKey = "runs/source.json",
            ),
        )
        whenever(inbox.drain()).thenReturn(events)
        whenever(inbox.size()).thenReturn(2)

        val controller = CleanupController(
            runCleanupService = runCleanupService,
            inbox = inbox,
            inboxProperties = inboxProperties,
            objectStorage = storage,
            staleKafkaSkipService = staleKafkaSkipService,
        )
        val response = controller.cleanupInbox().body!!

        assert(response.drained == 2)
        assert(response.deleted == 3) { "expected 3 deletes, got ${response.deleted}" }
        assert(response.failed == 0) { "expected 0 failures, got ${response.failed}" }
        verify(storage).delete("runs/abc/manifest.json")
        verify(storage).delete("runs/def/manifest.json")
        verify(storage).delete("runs/source.json")
    }

    @Test
    fun `cleanupInbox counts failed deletes when ObjectStorage throws`() {
        val events = listOf(
            ChunkConsumedEvent(
                runId = "r1",
                endpoint = "basic",
                chunkId = "c1",
                objectKey = "runs/bad/manifest.json",
                sourceObjectKey = "runs/bad-source.json",
            ),
        )
        whenever(inbox.drain()).thenReturn(events)
        whenever(inbox.size()).thenReturn(1)
        // objectKey delete fails; sourceObjectKey delete succeeds
        doThrow(RuntimeException("storage down")).whenever(storage)
            .delete("runs/bad/manifest.json")

        val controller = CleanupController(
            runCleanupService = runCleanupService,
            inbox = inbox,
            inboxProperties = inboxProperties,
            objectStorage = storage,
            staleKafkaSkipService = staleKafkaSkipService,
        )
        val response = controller.cleanupInbox().body!!

        assert(response.drained == 1)
        assert(response.deleted == 1) { "expected 1 success, got ${response.deleted}" }
        assert(response.failed == 1) { "expected 1 failure, got ${response.failed}" }
    }

    @Test
    fun `cleanupInbox returns zeros when inbox is empty`() {
        whenever(inbox.drain()).thenReturn(emptyList())
        whenever(inbox.size()).thenReturn(0)

        val controller = CleanupController(
            runCleanupService = runCleanupService,
            inbox = inbox,
            inboxProperties = inboxProperties,
            objectStorage = storage,
            staleKafkaSkipService = staleKafkaSkipService,
        )
        val response = controller.cleanupInbox().body!!

        assert(response.drained == 0)
        assert(response.deleted == 0)
        assert(response.failed == 0)
    }

    @Test
    fun `cleanupRuns delegates to RunCleanupService and returns result`() {
        whenever(runCleanupService.cleanupRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 3, bytesDeleted = 1024L, errors = 0, throttled = 0),
        )

        val controller = CleanupController(
            runCleanupService = runCleanupService,
            inbox = inbox,
            inboxProperties = inboxProperties,
            objectStorage = storage,
            staleKafkaSkipService = staleKafkaSkipService,
        )
        val response = controller.cleanupRuns().body!!

        assert(response.runsDeleted == 3)
        assert(response.bytesDeleted == 1024L)
    }

    @Test
    fun `cleanupCalculatorRuns delegates to RunCleanupService and returns result`() {
        whenever(runCleanupService.cleanupCalculatorRuns()).thenReturn(
            RunCleanupResult(runsDeleted = 5, bytesDeleted = 2048L, errors = 0, throttled = 0),
        )

        val controller = CleanupController(
            runCleanupService = runCleanupService,
            inbox = inbox,
            inboxProperties = inboxProperties,
            objectStorage = storage,
            staleKafkaSkipService = staleKafkaSkipService,
        )
        val response = controller.cleanupCalculatorRuns().body!!

        assert(response.runsDeleted == 5)
        assert(response.bytesDeleted == 2048L)
    }
}

// Suppress unused-import warning (we use ConsumerFactory / KafkaTemplate in the
// original Spring slice test which is replaced by this unit test).
@Suppress("unused")
private val unused = listOf(ConsumerFactory::class, KafkaTemplate::class)

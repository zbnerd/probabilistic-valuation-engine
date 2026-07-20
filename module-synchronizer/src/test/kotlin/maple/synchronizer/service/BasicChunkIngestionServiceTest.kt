package maple.synchronizer.service

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.port.out.ChunkFileReaderPort
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.consumer.ChunkConsumerTemplate
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.OcidMappingRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BasicChunkIngestionServiceTest {
    private val chunkFileReader = mock<ChunkFileReaderPort>()
    private val repository = mock<CharacterBasicRepository>()
    private val ocidRepo = mock<OcidMappingRepository>()
    private val template = mock<ChunkConsumerTemplate>()
    private val publisher = mock<KafkaChunkConsumedEventPublisher>()
    private val executor = mock<ExecutorService>()

    private val service = BasicChunkIngestionService(
        chunkFileReader = chunkFileReader,
        repository = repository,
        ocidMappingRepository = ocidRepo,
        chunkConsumerTemplate = template,
        consumedEventPublisher = publisher,
        executor = executor,
    )

    @Test
    fun `process returns terminal drop and skips template for non-character-basic endpoint`() {
        val event = makeEvent(endpoint = "ocid-lookup")

        val outcome = service.process(
            event = event,
            eventPayloadJson = "{}",
            topic = "t",
            messageKey = "k",
            urgent = false,
        ).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.TerminalDrop("ENDPOINT_MISMATCH"))
        verify(template, never()).submit(any())
    }

    @Test
    fun `process returns template outcome for character-basic endpoint`() {
        val event = makeEvent(endpoint = "character-basic")
        whenever(template.submit(any())).thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))

        val outcome = service.process(
            event = event,
            eventPayloadJson = "{}",
            topic = "t",
            messageKey = "k",
            urgent = false,
        ).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
        verify(template).submit(any())
    }

    private fun makeEvent(endpoint: String): SnapshotChunkReadyEvent = SnapshotChunkReadyEvent(
        eventId = "event-1",
        runId = "run-1",
        endpoint = endpoint,
        chunkId = "chunk-1",
        objectKey = "object-key",
        recordCount = 10,
        uncompressedBytes = 100L,
        compressedBytes = 50L,
        createdAt = Instant.parse("2026-06-06T00:00:00Z"),
    )
}

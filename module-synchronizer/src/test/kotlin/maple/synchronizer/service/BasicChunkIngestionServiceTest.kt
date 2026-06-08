package maple.synchronizer.service

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.synchronizer.consumer.ChunkConsumerTemplate
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.BasicChunkFileReader
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.concurrent.ExecutorService

class BasicChunkIngestionServiceTest {
    private val fileReader = mock<BasicChunkFileReader>()
    private val repository = mock<CharacterBasicRepository>()
    private val ocidRepo = mock<OcidMappingRepository>()
    private val template = mock<ChunkConsumerTemplate>()
    private val publisher = mock<KafkaChunkConsumedEventPublisher>()
    private val executor = mock<ExecutorService>()

    private val service = BasicChunkIngestionService(
        fileReader = fileReader,
        repository = repository,
        ocidMappingRepository = ocidRepo,
        chunkConsumerTemplate = template,
        consumedEventPublisher = publisher,
        executor = executor,
    )

    @Test
    fun `process returns false and skips template for non-character-basic endpoint`() {
        val event = makeEvent(endpoint = "ocid-lookup")

        val handled = service.process(
            event = event,
            eventPayloadJson = "{}",
            acknowledgment = mock<Acknowledgment>(),
            topic = "t",
            messageKey = "k",
            urgent = false,
        )

        assertFalse(handled)
        verify(template, never()).submit(any())
    }

    @Test
    fun `process returns true and submits template for character-basic endpoint`() {
        val event = makeEvent(endpoint = "character-basic")

        val handled = service.process(
            event = event,
            eventPayloadJson = "{}",
            acknowledgment = mock<Acknowledgment>(),
            topic = "t",
            messageKey = "k",
            urgent = false,
        )

        assertTrue(handled)
        verify(template).submit(any())
    }

    private fun makeEvent(endpoint: String): SnapshotChunkReadyEvent =
        SnapshotChunkReadyEvent(
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

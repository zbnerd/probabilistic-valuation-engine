package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import kotlinx.coroutines.runBlocking
import maple.calculator.parser.SnapshotEventParser
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.kafka.support.Acknowledgment

@ExtendWith(MockitoExtension::class)
class KafkaSnapshotChunkReadyConsumerTest {

    @Mock
    private lateinit var dispatchService: SnapshotDispatchService

    @Mock
    private lateinit var acknowledgment: Acknowledgment

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private lateinit var consumer: KafkaSnapshotChunkReadyConsumer

    private val event = SnapshotChunkReadyEvent(
        eventId = "evt-1",
        runId = "run-1",
        endpoint = "item-equipment",
        chunkId = "chunk-1",
        objectKey = "k",
        recordCount = 1,
        uncompressedBytes = 1L,
        compressedBytes = 1L,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val messageJson: String = objectMapper.writeValueAsString(event)

    @BeforeEach
    fun setUp() {
        consumer = KafkaSnapshotChunkReadyConsumer(
            eventParser = SnapshotEventParser(objectMapper),
            dispatchService = dispatchService,
        )
    }

    @Test
    fun `consume parses and delegates to dispatchService with Consumer label`() = runBlocking {
        consumer.consume(messageJson, acknowledgment)

        verify(dispatchService, times(1)).dispatch(event, acknowledgment, "Consumer")
    }

    @Test
    fun `consumeUrgent parses and delegates to dispatchService with URGENT label`() = runBlocking {
        consumer.consumeUrgent(messageJson, acknowledgment)

        verify(dispatchService, times(1)).dispatch(event, acknowledgment, "URGENT")
    }
}

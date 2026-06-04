package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class KafkaSnapshotChunkReadyConsumerTest {

    @Mock
    private lateinit var coordinator: CalculatorChunkProcessingCoordinator

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
            objectMapper = objectMapper,
            coordinator = coordinator,
            maxRetries = 2,
            retryBackoffMs = 5,
        )
    }

    @Test
    fun `consume ACKs on success`() = runBlocking {
        whenever(coordinator.handle(any())).thenReturn(Unit)

        consumer.consume(messageJson, acknowledgment)

        verify(coordinator, times(1)).handle(any())
        verify(acknowledgment, times(1)).acknowledge()
    }

    @Test
    fun `consume retries transient failure then ACKs`() = runBlocking {
        whenever(coordinator.handle(any()))
            .thenThrow(RuntimeException("transient-1"))
            .thenThrow(RuntimeException("transient-2"))
            .thenReturn(Unit)

        consumer.consume(messageJson, acknowledgment)

        verify(coordinator, times(3)).handle(any())
        verify(acknowledgment, times(1)).acknowledge()
    }

    @Test
    fun `consume throws after exhausting retries without ACKing`() {
        runBlocking {
            whenever(coordinator.handle(any())).thenThrow(RuntimeException("permanent"))
        }

        assertThrows<RuntimeException> {
            consumer.consume(messageJson, acknowledgment)
        }

        runBlocking {
            verify(coordinator, times(3)).handle(any())
            verify(acknowledgment, never()).acknowledge()
        }
    }

    @Test
    fun `consume propagates CancellationException without retry`() {
        runBlocking {
            whenever(coordinator.handle(any())).thenThrow(CancellationException("cancelled"))
        }

        assertThrows<CancellationException> {
            consumer.consume(messageJson, acknowledgment)
        }

        runBlocking {
            verify(coordinator, times(1)).handle(any())
            verify(acknowledgment, never()).acknowledge()
        }
    }
}

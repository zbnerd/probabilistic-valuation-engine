package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.calculator.parser.SnapshotEventParser
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class KafkaSnapshotChunkReadyConsumerTest {

    @Mock
    private lateinit var dispatchService: SnapshotDispatchService

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
    fun `consume parses and delegates once with Consumer label`() {
        whenever(dispatchService.dispatch(event, "Consumer"))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))

        val outcome = consumer.consume(messageJson).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
        verify(dispatchService, times(1)).dispatch(event, "Consumer")
    }

    @Test
    fun `consumeUrgent parses and delegates once with URGENT label`() {
        val failure = IllegalStateException("failed")
        whenever(dispatchService.dispatch(event, "URGENT"))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure)))

        val outcome = consumer.consumeUrgent(messageJson).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
        verify(dispatchService, times(1)).dispatch(event, "URGENT")
    }

    @Test
    fun `malformed payload is invalid`() {
        val outcome = consumer.consume("not-json").toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.InvalidMessage("INVALID_MESSAGE"))
    }
}

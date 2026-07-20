package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.calculator.parser.SnapshotEventParser
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CalculatorSnapshotSubscriptionTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val dispatchService = mock<SnapshotDispatchService>()
    private val consumer = KafkaSnapshotChunkReadyConsumer(SnapshotEventParser(objectMapper), dispatchService)
    private val subscriptions = CalculatorSnapshotSubscription(
        consumer = consumer,
        normalTopic = "external-api.snapshot.chunk-ready",
        normalGroupId = "calculator-snapshot-chunk-processor",
        urgentTopic = "external-api.urgent.snapshot.chunk-ready",
        urgentGroupId = "calculator-urgent-chunk-processor",
    )
    private val event = SnapshotChunkReadyEvent(
        eventId = "evt-1",
        runId = "run-1",
        endpoint = "item-equipment",
        chunkId = "chunk-1",
        objectKey = "key",
        recordCount = 1,
        uncompressedBytes = 10,
        compressedBytes = 5,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `normal subscription preserves topic group and waits for one dispatch attempt`() {
        val dispatchCompletion = CompletableFuture<DeliveryOutcome>()
        whenever(dispatchService.dispatch(event, "Consumer")).thenReturn(dispatchCompletion)
        val subscription = subscriptions.normalSubscription()

        val delivery = subscription.handler.handle(objectMapper.writeValueAsString(event), context()).toCompletableFuture()

        assertThat(subscription.id).isEqualTo("calculator-snapshot-normal")
        assertThat(subscription.topics).containsExactly("external-api.snapshot.chunk-ready")
        assertThat(subscription.groupId).isEqualTo("calculator-snapshot-chunk-processor")
        assertThat(delivery).isNotDone()
        verify(dispatchService, times(1)).dispatch(event, "Consumer")

        dispatchCompletion.complete(DeliveryOutcome.Success)
        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
    }

    @Test
    fun `urgent subscription failure is retryable without an internal second attempt`() {
        val failure = IllegalStateException("coordinator failed")
        whenever(dispatchService.dispatch(event, "URGENT"))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure)))
        val subscription = subscriptions.urgentSubscription()

        val outcome = subscription.handler
            .handle(objectMapper.writeValueAsString(event), context())
            .toCompletableFuture()
            .resultNow()

        assertThat(subscription.id).isEqualTo("calculator-snapshot-urgent")
        assertThat(subscription.topics).containsExactly("external-api.urgent.snapshot.chunk-ready")
        assertThat(subscription.groupId).isEqualTo("calculator-urgent-chunk-processor")
        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
        verify(dispatchService, times(1)).dispatch(event, "URGENT")
    }

    @Test
    fun `malformed payload is invalid and never dispatches`() {
        val outcome = subscriptions.normalSubscription().handler
            .handle("not-json", context())
            .toCompletableFuture()
            .resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.InvalidMessage("INVALID_MESSAGE"))
        verify(dispatchService, never()).dispatch(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    private fun context(): DeliveryContext = DeliveryContext(
        listenerId = "calculator-snapshot-normal",
        topic = "external-api.snapshot.chunk-ready",
        partition = 0,
        offset = 1L,
        timestamp = Instant.EPOCH,
        key = "key",
        deliveryAttempt = 1,
    )
}

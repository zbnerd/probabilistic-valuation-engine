package maple.synchronizer.consumer

import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SynchronizerSubscriptionsTest {
    private val basicConsumer = mock<BasicSnapshotChunkConsumer>()
    private val resultConsumer = mock<KafkaResultChunkConsumer>()
    private val ocidConsumer = mock<OcidLookupRunConsumer>()
    private val subscriptions = SynchronizerSubscriptions(
        basicConsumer = basicConsumer,
        resultConsumer = resultConsumer,
        ocidConsumer = ocidConsumer,
        basicTopic = "external-api.snapshot.chunk-ready",
        basicGroupId = "synchronizer-basic-chunk-consumer",
        urgentBasicTopic = "external-api.urgent.snapshot.chunk-ready",
        urgentBasicGroupId = "synchronizer-urgent-basic-chunk-consumer",
        resultTopic = "calculator.result.chunk-ready",
        resultGroupId = "synchronizer-result-chunk-consumer",
        ocidTopic = "external-api.ocid.lookup-ready",
        ocidGroupId = "synchronizer-ocid-lookup-consumer",
        concurrency = 3,
    )

    @Test
    fun `basic and urgent subscriptions preserve topology and delegate outcomes`() {
        val context = context("synchronizer-basic", "external-api.snapshot.chunk-ready")
        whenever(basicConsumer.consume("basic", context))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))
        whenever(basicConsumer.consumeUrgentBasic("urgent", context))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Backpressure(java.time.Duration.ofSeconds(1))))

        val basic = subscriptions.basicSubscription()
        val urgent = subscriptions.urgentBasicSubscription()

        assertThat(basic.id).isEqualTo("synchronizer-basic")
        assertThat(basic.topics).containsExactly("external-api.snapshot.chunk-ready")
        assertThat(basic.groupId).isEqualTo("synchronizer-basic-chunk-consumer")
        assertThat(basic.concurrency).isEqualTo(3)
        assertThat(basic.handler.handle("basic", context).toCompletableFuture().resultNow())
            .isEqualTo(DeliveryOutcome.Success)
        assertThat(urgent.id).isEqualTo("synchronizer-urgent-basic")
        assertThat(urgent.topics).containsExactly("external-api.urgent.snapshot.chunk-ready")
        assertThat(urgent.groupId).isEqualTo("synchronizer-urgent-basic-chunk-consumer")
        assertThat(urgent.handler.handle("urgent", context).toCompletableFuture().resultNow())
            .isInstanceOf(DeliveryOutcome.Backpressure::class.java)
        verify(basicConsumer).consume("basic", context)
        verify(basicConsumer).consumeUrgentBasic("urgent", context)
    }

    @Test
    fun `result subscription preserves topology and delegates outcome`() {
        val context = context("synchronizer-result", "calculator.result.chunk-ready")
        val failure = IllegalStateException("retry")
        whenever(resultConsumer.consume("result", context))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure)))

        val result = subscriptions.resultSubscription()

        assertThat(result.id).isEqualTo("synchronizer-result")
        assertThat(result.topics).containsExactly("calculator.result.chunk-ready")
        assertThat(result.groupId).isEqualTo("synchronizer-result-chunk-consumer")
        assertThat(result.handler.handle("result", context).toCompletableFuture().resultNow())
            .isEqualTo(DeliveryOutcome.Retryable(failure))
        verify(resultConsumer).consume("result", context)
    }

    @Test
    fun `ocid subscription preserves topology and delegates outcome`() {
        val context = context("synchronizer-ocid-lookup", "external-api.ocid.lookup-ready")
        whenever(ocidConsumer.consume("ocid", context))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))

        val ocid = subscriptions.ocidSubscription()

        assertThat(ocid.id).isEqualTo("synchronizer-ocid-lookup")
        assertThat(ocid.topics).containsExactly("external-api.ocid.lookup-ready")
        assertThat(ocid.groupId).isEqualTo("synchronizer-ocid-lookup-consumer")
        assertThat(ocid.handler.handle("ocid", context).toCompletableFuture().resultNow())
            .isEqualTo(DeliveryOutcome.Success)
        verify(ocidConsumer).consume("ocid", context)
    }

    private fun context(listenerId: String, topic: String): DeliveryContext = DeliveryContext(
        listenerId = listenerId,
        topic = topic,
        partition = 0,
        offset = 1,
        timestamp = Instant.EPOCH,
        key = "key",
        deliveryAttempt = 1,
    )
}

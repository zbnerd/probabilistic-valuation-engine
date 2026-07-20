package maple.pipeline.messaging.adapter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordFactory
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import maple.pipeline.messaging.dlt.DltPublisher
import maple.pipeline.messaging.metrics.DeliveryMetrics
import maple.pipeline.messaging.policy.DeliveryRetryPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.kafka.support.Acknowledgment
import org.junit.jupiter.api.Test

class PartitionLaneRegistryTest {
    @Test
    fun `different partitions complete acknowledge and resume independently`() {
        val partitionZero = CompletableFuture<DeliveryOutcome>()
        val partitionOne = CompletableFuture<DeliveryOutcome>()
        val subscription = subscription { _, context ->
            if (context.partition == 0) partitionZero else partitionOne
        }
        val control = RecordingPartitionControl()
        val registry = registry()
        val tp0 = TopicPartition("topic", 0)
        val tp1 = TopicPartition("topic", 1)
        val ack0 = mock<Acknowledgment>()
        val ack1 = mock<Acknowledgment>()
        registry.onAssigned(subscription, listOf(tp0, tp1), control)

        registry.offer(subscription, record(0), ack0)
        registry.offer(subscription, record(1), ack1)
        partitionOne.complete(DeliveryOutcome.Success)

        verify(ack1).acknowledge()
        verify(ack0, never()).acknowledge()
        assertThat(control.resumed).containsExactly(tp1)

        partitionZero.complete(DeliveryOutcome.Success)
        verify(ack0).acknowledge()
        assertThat(control.resumed).containsExactlyInAnyOrder(tp0, tp1)
    }

    @Test
    fun `reassignment fences the old generation`() {
        val oldCompletion = CompletableFuture<DeliveryOutcome>()
        val subscription = subscription { _, _ -> oldCompletion }
        val control = RecordingPartitionControl()
        val registry = registry()
        val topicPartition = TopicPartition("topic", 0)
        val oldAck = mock<Acknowledgment>()
        registry.onAssigned(subscription, listOf(topicPartition), control)
        val oldGeneration = registry.currentOwnership(subscription.id, topicPartition)?.generation
        registry.offer(subscription, record(0), oldAck)

        registry.onRevoked(subscription.id, listOf(topicPartition))
        registry.onAssigned(subscription, listOf(topicPartition), control)
        val newGeneration = registry.currentOwnership(subscription.id, topicPartition)?.generation
        oldCompletion.complete(DeliveryOutcome.Success)

        assertThat(newGeneration).isGreaterThan(oldGeneration)
        verify(oldAck, never()).acknowledge()
    }

    private fun registry(): PartitionLaneRegistry {
        val metrics = DeliveryMetrics(SimpleMeterRegistry())
        return PartitionLaneRegistry(
            adapter = KafkaDeliveryAdapter(
                retryPolicy = DeliveryRetryPolicy(),
                dltPublisher = DltPublisher { _, _, _ -> CompletableFuture.completedFuture(null) },
                dltRecordFactory = DltRecordFactory(),
                deliveryExecutor = Executor(Runnable::run),
                retryScheduler = ManualScheduler(),
                metrics = metrics,
            ),
            maxQueuedRecords = 50,
            metrics = metrics,
        )
    }

    private fun subscription(
        handler: (String, maple.pipeline.messaging.contract.DeliveryContext) -> CompletableFuture<DeliveryOutcome>,
    ): PipelineSubscription = PipelineSubscription(
        id = "listener",
        topics = listOf("topic"),
        groupId = "group",
        handler = handler,
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    private fun record(partition: Int): ConsumerRecord<String, String> =
        ConsumerRecord("topic", partition, 7L, "key", "{}")
}

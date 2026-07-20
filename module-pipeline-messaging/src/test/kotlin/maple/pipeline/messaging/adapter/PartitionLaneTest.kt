package maple.pipeline.messaging.adapter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import maple.pipeline.messaging.contract.DeliveryHandler
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.kafka.support.Acknowledgment
import org.junit.jupiter.api.Test

class PartitionLaneTest {
    @Test
    fun `same partition waits for prior ACK eligibility before invoking next offset`() {
        val firstCompletion = CompletableFuture<DeliveryOutcome>()
        val calls = mutableListOf<String>()
        val handlerCalls = mutableListOf<Long>()
        val subscription = subscription { _, context ->
            handlerCalls += context.offset
            if (context.offset == 10L) firstCompletion else completed(DeliveryOutcome.Success)
        }
        val control = RecordingPartitionControl(calls)
        val ownership = ownership()
        val lane = lane(subscription, ownership, control)
        val firstAck = acknowledgment { calls += "ack-10" }
        val secondAck = acknowledgment { calls += "ack-11" }

        lane.offer(record(10L), firstAck)
        lane.offer(record(11L), secondAck)

        assertThat(handlerCalls).containsExactly(10L)
        assertThat(calls).containsExactly("pause")

        firstCompletion.complete(DeliveryOutcome.Success)

        assertThat(handlerCalls).containsExactly(10L, 11L)
        assertThat(calls).containsExactly("pause", "ack-10", "ack-11", "resume")
    }

    @Test
    fun `revoked lane never acknowledges or resumes stale completion`() {
        val completion = CompletableFuture<DeliveryOutcome>()
        val current = AtomicBoolean(true)
        val control = RecordingPartitionControl()
        val ack = mock<Acknowledgment>()
        val lane = lane(
            subscription { _, _ -> completion },
            ownership(current),
            control,
        )

        lane.offer(record(10L), ack)
        current.set(false)
        lane.revoke()
        completion.complete(DeliveryOutcome.Success)

        verify(ack, never()).acknowledge()
        assertThat(control.resumed).isEmpty()
    }

    @Test
    fun `queue high water violation retains records and degrades health`() {
        val completion = CompletableFuture<DeliveryOutcome>()
        val metrics = DeliveryMetrics(SimpleMeterRegistry())
        val lane = lane(
            subscription { _, _ -> completion },
            ownership(),
            RecordingPartitionControl(),
            metrics = metrics,
            maxQueuedRecords = 1,
        )

        lane.offer(record(10L), mock())
        lane.offer(record(11L), mock())
        lane.offer(record(12L), mock())

        assertThat(lane.queuedRecordCount()).isEqualTo(2)
        assertThat(metrics.isHealthy()).isFalse()
    }

    private fun lane(
        subscription: PipelineSubscription,
        ownership: PartitionOwnership,
        control: PartitionControl,
        metrics: DeliveryMetrics = DeliveryMetrics(SimpleMeterRegistry()),
        maxQueuedRecords: Int = 50,
    ): PartitionLane = PartitionLane(
        subscription = subscription,
        ownership = ownership,
        adapter = KafkaDeliveryAdapter(
            retryPolicy = DeliveryRetryPolicy(),
            dltPublisher = DltPublisher { _, _, _ -> CompletableFuture.completedFuture(null) },
            dltRecordFactory = DltRecordFactory(),
            deliveryExecutor = Executor(Runnable::run),
            retryScheduler = ManualScheduler(),
            metrics = metrics,
        ),
        partitionControl = control,
        maxQueuedRecords = maxQueuedRecords,
        metrics = metrics,
    )

    private fun subscription(handler: DeliveryHandler): PipelineSubscription = PipelineSubscription(
        id = "listener",
        topics = listOf("topic"),
        groupId = "group",
        handler = handler,
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    private fun ownership(current: AtomicBoolean = AtomicBoolean(true)): PartitionOwnership = PartitionOwnership(
        listenerId = "listener",
        topicPartition = TopicPartition("topic", 0),
        generation = 1L,
        current = current::get,
    )

    private fun record(offset: Long): ConsumerRecord<String, String> = ConsumerRecord("topic", 0, offset, "key", "{}")

    private fun acknowledgment(action: () -> Unit): Acknowledgment = mock {
        on { acknowledge() } doAnswer {
            action()
            Unit
        }
    }

    private fun completed(outcome: DeliveryOutcome): CompletableFuture<DeliveryOutcome> =
        CompletableFuture.completedFuture(outcome)
}

internal class RecordingPartitionControl(
    private val calls: MutableList<String> = mutableListOf(),
) : PartitionControl {
    val paused = mutableListOf<TopicPartition>()
    val resumed = mutableListOf<TopicPartition>()

    override fun pausePartition(topicPartition: TopicPartition) {
        paused += topicPartition
        calls += "pause"
    }

    override fun resumePartition(topicPartition: TopicPartition) {
        resumed += topicPartition
        calls += "resume"
    }
}

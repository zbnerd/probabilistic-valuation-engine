package maple.pipeline.messaging.adapter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.metrics.DeliveryMetrics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.support.Acknowledgment

class PartitionLaneRegistry(
    private val adapter: KafkaDeliveryAdapter,
    private val maxQueuedRecords: Int,
    private val metrics: DeliveryMetrics,
) {
    private data class LaneKey(val listenerId: String, val topicPartition: TopicPartition)

    private val generations = ConcurrentHashMap<LaneKey, AtomicLong>()
    private val lanes = ConcurrentHashMap<LaneKey, PartitionLane>()
    private val ownerships = ConcurrentHashMap<LaneKey, PartitionOwnership>()

    fun onAssigned(
        subscription: PipelineSubscription,
        partitions: Collection<TopicPartition>,
        partitionControl: PartitionControl,
    ) {
        partitions.forEach { topicPartition -> assign(subscription, topicPartition, partitionControl) }
    }

    fun onRevoked(listenerId: String, partitions: Collection<TopicPartition>) {
        partitions.forEach { topicPartition -> revoke(LaneKey(listenerId, topicPartition)) }
    }

    fun offer(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment,
    ) {
        val key = LaneKey(subscription.id, TopicPartition(record.topic(), record.partition()))
        val lane = lanes[key]
        if (lane == null) {
            metrics.recordInvariant(subscription.id, record.topic(), "UNASSIGNED_RECORD")
        } else {
            lane.offer(record, acknowledgment)
        }
    }

    fun currentOwnership(listenerId: String, topicPartition: TopicPartition): PartitionOwnership? =
        ownerships[LaneKey(listenerId, topicPartition)]

    private fun assign(
        subscription: PipelineSubscription,
        topicPartition: TopicPartition,
        partitionControl: PartitionControl,
    ) {
        val key = LaneKey(subscription.id, topicPartition)
        lanes.remove(key)?.revoke()
        val generation = generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        val ownership = PartitionOwnership(
            listenerId = subscription.id,
            topicPartition = topicPartition,
            generation = generation,
            current = { generations[key]?.get() == generation },
        )
        val lane = PartitionLane(
            subscription = subscription,
            ownership = ownership,
            adapter = adapter,
            partitionControl = partitionControl,
            maxQueuedRecords = maxQueuedRecords,
            metrics = metrics,
        )
        ownerships[key] = ownership
        lanes[key] = lane
    }

    private fun revoke(key: LaneKey) {
        generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        ownerships.remove(key)
        lanes.remove(key)?.revoke()
    }
}

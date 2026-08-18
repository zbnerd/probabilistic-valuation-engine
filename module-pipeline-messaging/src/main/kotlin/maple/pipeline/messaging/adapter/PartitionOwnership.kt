package maple.pipeline.messaging.adapter

import org.apache.kafka.common.TopicPartition

class PartitionOwnership(
    val listenerId: String,
    val topicPartition: TopicPartition,
    val generation: Long,
    private val current: () -> Boolean,
) {
    fun isCurrent(): Boolean = current()
}

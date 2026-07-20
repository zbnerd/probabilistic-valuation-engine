package maple.pipeline.messaging.adapter

import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.listener.MessageListenerContainer

interface PartitionControl {
    fun pausePartition(topicPartition: TopicPartition)

    fun resumePartition(topicPartition: TopicPartition)
}

class ContainerPartitionControl(
    private val container: MessageListenerContainer,
) : PartitionControl {
    override fun pausePartition(topicPartition: TopicPartition) {
        container.pausePartition(topicPartition)
    }

    override fun resumePartition(topicPartition: TopicPartition) {
        container.resumePartition(topicPartition)
    }
}

package maple.pipeline.messaging.adapter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import maple.pipeline.messaging.contract.PipelineSubscription
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener

class PipelineKafkaEndpointRegistry(
    private val subscriptions: List<PipelineSubscription>,
    private val containerFactory: ConcurrentKafkaListenerContainerFactory<String, String>,
    private val laneRegistry: PartitionLaneRegistry,
) : SmartLifecycle {
    private val running = AtomicBoolean()
    private val containers = CopyOnWriteArrayList<ConcurrentMessageListenerContainer<String, String>>()

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        val created = subscriptions.map(::createContainer)
        containers.addAll(created)
        created.forEach(ConcurrentMessageListenerContainer<String, String>::start)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        containers.forEach(ConcurrentMessageListenerContainer<String, String>::stop)
        containers.clear()
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 0

    private fun createContainer(
        subscription: PipelineSubscription,
    ): ConcurrentMessageListenerContainer<String, String> {
        val container = containerFactory.createContainer(*subscription.topics.toTypedArray())
        val partitionControl = ContainerPartitionControl(container)
        container.setBeanName(subscription.id)
        container.setConcurrency(subscription.concurrency)
        container.containerProperties.setGroupId(subscription.groupId)
        container.containerProperties.setMessageListener(
            AcknowledgingConsumerAwareMessageListener<String, String> { record, acknowledgment, _ ->
                laneRegistry.offer(subscription, record, requireNotNull(acknowledgment))
            },
        )
        container.containerProperties.setConsumerRebalanceListener(rebalanceListener(subscription, partitionControl))
        return container
    }

    private fun rebalanceListener(
        subscription: PipelineSubscription,
        partitionControl: PartitionControl,
    ): ConsumerAwareRebalanceListener = object : ConsumerAwareRebalanceListener {
        override fun onPartitionsAssigned(
            consumer: Consumer<*, *>,
            partitions: Collection<TopicPartition>,
        ) {
            laneRegistry.onAssigned(subscription, partitions, partitionControl)
        }

        override fun onPartitionsRevokedBeforeCommit(
            consumer: Consumer<*, *>,
            partitions: Collection<TopicPartition>,
        ) {
            laneRegistry.onRevoked(subscription.id, partitions)
        }

        override fun onPartitionsLost(
            consumer: Consumer<*, *>,
            partitions: Collection<TopicPartition>,
        ) {
            laneRegistry.onRevoked(subscription.id, partitions)
        }
    }
}

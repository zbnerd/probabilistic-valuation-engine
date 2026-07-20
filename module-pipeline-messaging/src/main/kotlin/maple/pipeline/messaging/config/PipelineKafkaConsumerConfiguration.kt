package maple.pipeline.messaging.config

import io.micrometer.core.instrument.MeterRegistry
import maple.pipeline.messaging.adapter.KafkaDeliveryAdapter
import maple.pipeline.messaging.adapter.PartitionLaneRegistry
import maple.pipeline.messaging.adapter.PipelineDeliveryExecutors
import maple.pipeline.messaging.adapter.PipelineKafkaEndpointRegistry
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordFactory
import maple.pipeline.messaging.dlt.KafkaDltPublisher
import maple.pipeline.messaging.dlt.SafeDeadLetterPublishingRecoverer
import maple.pipeline.messaging.metrics.DeliveryMetrics
import maple.pipeline.messaging.policy.DeliveryRetryPolicy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties

@Configuration
class PipelineKafkaConsumerConfiguration {
    @Bean(name = ["pipelineKafkaListenerContainerFactory"])
    fun pipelineKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().also { factory ->
            factory.consumerFactory = consumerFactory
            factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            factory.containerProperties.isAsyncAcks = false
        }

    @Bean
    fun deliveryRetryPolicy(): DeliveryRetryPolicy = DeliveryRetryPolicy()

    @Bean
    fun deliveryMetrics(meterRegistry: MeterRegistry): DeliveryMetrics = DeliveryMetrics(meterRegistry)

    @Bean(destroyMethod = "close")
    fun pipelineDeliveryExecutors(metrics: DeliveryMetrics): PipelineDeliveryExecutors =
        PipelineDeliveryExecutors(metrics)

    @Bean
    fun dltRecordFactory(): DltRecordFactory = DltRecordFactory()

    @Bean
    fun safeDeadLetterPublishingRecoverer(
        kafkaTemplate: KafkaTemplate<String, String>,
    ): SafeDeadLetterPublishingRecoverer = SafeDeadLetterPublishingRecoverer(kafkaTemplate)

    @Bean
    fun kafkaDltPublisher(
        recoverer: SafeDeadLetterPublishingRecoverer,
        executors: PipelineDeliveryExecutors,
    ): KafkaDltPublisher = KafkaDltPublisher(recoverer, executors.dltExecutor)

    @Bean
    fun kafkaDeliveryAdapter(
        retryPolicy: DeliveryRetryPolicy,
        dltPublisher: KafkaDltPublisher,
        dltRecordFactory: DltRecordFactory,
        executors: PipelineDeliveryExecutors,
        metrics: DeliveryMetrics,
    ): KafkaDeliveryAdapter = KafkaDeliveryAdapter(
        retryPolicy = retryPolicy,
        dltPublisher = dltPublisher,
        dltRecordFactory = dltRecordFactory,
        deliveryExecutor = executors.deliveryExecutor,
        retryScheduler = executors.retryScheduler,
        metrics = metrics,
    )

    @Bean
    fun partitionLaneRegistry(
        adapter: KafkaDeliveryAdapter,
        metrics: DeliveryMetrics,
        @Value("\${spring.kafka.consumer.max-poll-records:500}") maxQueuedRecords: Int,
    ): PartitionLaneRegistry = PartitionLaneRegistry(adapter, maxQueuedRecords, metrics)

    @Bean
    fun pipelineKafkaEndpointRegistry(
        subscriptions: List<PipelineSubscription>,
        @Qualifier("pipelineKafkaListenerContainerFactory")
        containerFactory: ConcurrentKafkaListenerContainerFactory<String, String>,
        laneRegistry: PartitionLaneRegistry,
    ): PipelineKafkaEndpointRegistry = PipelineKafkaEndpointRegistry(
        subscriptions = subscriptions,
        containerFactory = containerFactory,
        laneRegistry = laneRegistry,
    )
}

package maple.synchronizer.consumer

import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SynchronizerSubscriptions(
    private val basicConsumer: BasicSnapshotChunkConsumer,
    private val resultConsumer: KafkaResultChunkConsumer,
    private val ocidConsumer: OcidLookupRunConsumer,
    @Value("\${synchronizer.kafka.basic-chunk-ready-topic}") private val basicTopic: String,
    @Value("\${synchronizer.kafka.basic-consumer-group-id}") private val basicGroupId: String,
    @Value("\${synchronizer.kafka.urgent-basic-chunk-ready-topic}") private val urgentBasicTopic: String,
    @Value("\${synchronizer.kafka.urgent-basic-consumer-group-id}") private val urgentBasicGroupId: String,
    @Value("\${synchronizer.kafka.result-chunk-ready-topic}") private val resultTopic: String,
    @Value("\${synchronizer.kafka.consumer-group-id}") private val resultGroupId: String,
    @Value("\${synchronizer.kafka.ocid-lookup-topic}") private val ocidTopic: String,
    @Value("\${synchronizer.kafka.ocid-lookup-consumer-group-id}") private val ocidGroupId: String,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int = 1,
) {
    @Bean
    fun basicSubscription(): PipelineSubscription = PipelineSubscription(
        id = "synchronizer-basic",
        topics = listOf(basicTopic),
        groupId = basicGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler(basicConsumer::consume),
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    fun urgentBasicSubscription(): PipelineSubscription = PipelineSubscription(
        id = "synchronizer-urgent-basic",
        topics = listOf(urgentBasicTopic),
        groupId = urgentBasicGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler(basicConsumer::consumeUrgentBasic),
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    fun resultSubscription(): PipelineSubscription = PipelineSubscription(
        id = "synchronizer-result",
        topics = listOf(resultTopic),
        groupId = resultGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler(resultConsumer::consume),
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    @ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
    fun ocidSubscription(): PipelineSubscription = PipelineSubscription(
        id = "synchronizer-ocid-lookup",
        topics = listOf(ocidTopic),
        groupId = ocidGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler(ocidConsumer::consume),
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )
}

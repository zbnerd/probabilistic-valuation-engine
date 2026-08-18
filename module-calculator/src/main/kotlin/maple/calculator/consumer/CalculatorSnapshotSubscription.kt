package maple.calculator.consumer

import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CalculatorSnapshotSubscription(
    private val consumer: KafkaSnapshotChunkReadyConsumer,
    @Value("\${calculator.kafka.snapshot-chunk-ready-topic}") private val normalTopic: String,
    @Value("\${calculator.kafka.consumer-group-id}") private val normalGroupId: String,
    @Value("\${calculator.kafka.urgent-snapshot-chunk-ready-topic}") private val urgentTopic: String,
    @Value("\${calculator.kafka.urgent-consumer-group-id}") private val urgentGroupId: String,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int,
) {
    @Bean
    fun normalSubscription(): PipelineSubscription = PipelineSubscription(
        id = "calculator-snapshot-normal",
        topics = listOf(normalTopic),
        groupId = normalGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler { payload, _ -> consumer.consume(payload) },
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    fun urgentSubscription(): PipelineSubscription = PipelineSubscription(
        id = "calculator-snapshot-urgent",
        topics = listOf(urgentTopic),
        groupId = urgentGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler { payload, _ -> consumer.consumeUrgent(payload) },
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )
}

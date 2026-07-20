package maple.cleanup.inbox

import java.time.Clock
import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CleanupInboxSubscription(
    private val properties: InboxProperties,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int,
) {
    @Bean
    @ConditionalOnProperty(name = ["cleanup-inbox.auto-start"], havingValue = "true", matchIfMissing = true)
    fun cleanupInboxPipelineSubscription(inbox: ConsumedChunkInbox): PipelineSubscription = PipelineSubscription(
        id = "cleanup-inbox",
        topics = listOf(properties.topic),
        groupId = properties.consumerGroup,
        concurrency = concurrency,
        handler = DeliveryHandler(inbox::consume),
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    fun cleanupInboxClock(): Clock = Clock.systemUTC()
}

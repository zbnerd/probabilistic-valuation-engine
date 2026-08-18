package maple.externalapi.messaging

import maple.externalapi.auth.AuthCharacterFetchHandler
import maple.externalapi.auth.AuthRequestDltSanitizer
import maple.externalapi.urgent.UrgentCharacterRequestConsumer
import maple.pipeline.messaging.contract.DeliveryHandler
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ExternalApiSubscriptions(
    private val urgentConsumer: UrgentCharacterRequestConsumer,
    private val authHandler: AuthCharacterFetchHandler,
    private val authSanitizer: AuthRequestDltSanitizer,
    @Value("\${external-api.urgent.request-topic}") private val urgentTopic: String,
    @Value("\${external-api.urgent.consumer-group-id}") private val urgentGroupId: String,
    @Value("\${auth.kafka.character-fetch-request-topic}") private val authTopic: String,
    @Value("\${auth.kafka.request-consumer-group-id}") private val authGroupId: String,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int,
) {
    @Bean
    @ConditionalOnProperty(name = ["external-api.urgent.enabled"], havingValue = "true")
    fun urgentSubscription(): PipelineSubscription = PipelineSubscription(
        id = "external-api-urgent",
        topics = listOf(urgentTopic),
        groupId = urgentGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler { payload, _ -> urgentConsumer.consume(payload) },
        dltSanitizer = DltRecordSanitizer.PassThrough,
    )

    @Bean
    fun authSubscription(): PipelineSubscription = PipelineSubscription(
        id = "external-api-auth-character-fetch",
        topics = listOf(authTopic),
        groupId = authGroupId,
        concurrency = concurrency,
        handler = DeliveryHandler { payload, context -> authHandler.handle(payload, context.key) },
        dltSanitizer = authSanitizer,
    )
}

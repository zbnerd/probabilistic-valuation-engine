package maple.externalapi.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.externalapi.auth.AuthCharacterFetchConsumer
import maple.externalapi.auth.AuthRequestDltSanitizer
import maple.externalapi.urgent.UrgentCharacterRequestConsumer
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

class ExternalApiSubscriptionsTest {
    private val urgentConsumer = mock<UrgentCharacterRequestConsumer>()
    private val authConsumer = mock<AuthCharacterFetchConsumer>()
    private val authSanitizer = mock<AuthRequestDltSanitizer>()
    private val subscriptions = ExternalApiSubscriptions(
        urgentConsumer = urgentConsumer,
        authConsumer = authConsumer,
        authSanitizer = authSanitizer,
        urgentTopic = "urgent-character-request",
        urgentGroupId = "external-api-urgent-processor",
        authTopic = "auth-character-fetch-request",
        authGroupId = "module-external-api-auth-consumer",
        concurrency = 2,
    )

    @Test
    fun `urgent subscription preserves topology and waits for handler outcome`() {
        val completion = CompletableFuture<DeliveryOutcome>()
        whenever(urgentConsumer.consume("urgent")).thenReturn(completion)
        val subscription = subscriptions.urgentSubscription()

        val delivery = subscription.handler.handle("urgent", context()).toCompletableFuture()

        assertThat(subscription.id).isEqualTo("external-api-urgent")
        assertThat(subscription.topics).containsExactly("urgent-character-request")
        assertThat(subscription.groupId).isEqualTo("external-api-urgent-processor")
        assertThat(subscription.concurrency).isEqualTo(2)
        assertThat(subscription.dltSanitizer).isEqualTo(DltRecordSanitizer.PassThrough)
        assertThat(delivery).isNotDone()
        verify(urgentConsumer).consume("urgent")

        completion.complete(DeliveryOutcome.Success)
        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
    }

    @Test
    fun `auth subscription preserves topology key and secret sanitizer`() {
        val context = context(key = "event-1")
        val failure = IllegalStateException("send failed")
        whenever(authConsumer.consume("auth", "event-1"))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure)))
        val subscription = subscriptions.authSubscription()

        val outcome = subscription.handler.handle("auth", context).toCompletableFuture().resultNow()

        assertThat(subscription.id).isEqualTo("external-api-auth-character-fetch")
        assertThat(subscription.topics).containsExactly("auth-character-fetch-request")
        assertThat(subscription.groupId).isEqualTo("module-external-api-auth-consumer")
        assertThat(subscription.dltSanitizer).isSameAs(authSanitizer)
        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
        verify(authConsumer).consume("auth", "event-1")
    }

    @Test
    fun `auth response send completes before Success`() {
        val fixture = authFixture()
        val send = CompletableFuture<SendResult<String, String>>()
        whenever(fixture.kafkaTemplate.send(eq("auth-response"), eq("event-1"), any())).thenReturn(send)

        val delivery = fixture.subscriptions.authSubscription().handler
            .handle(fixture.payload, context(key = "event-1"))
            .toCompletableFuture()

        assertThat(delivery).isNotDone()
        send.complete(null)
        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
    }

    @Test
    fun `auth response send failure is Retryable`() {
        val fixture = authFixture()
        val failure = IllegalStateException("send failed")
        whenever(fixture.kafkaTemplate.send(eq("auth-response"), eq("event-1"), any()))
            .thenReturn(CompletableFuture.failedFuture(failure))

        val outcome = fixture.subscriptions.authSubscription().handler
            .handle(fixture.payload, context(key = "event-1"))
            .toCompletableFuture()
            .resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
    }

    private fun authFixture(): AuthFixture {
        val objectMapper = jacksonObjectMapper().findAndRegisterModules()
        val client = mock<NexonAuthClient>()
        val kafkaTemplate = mock<KafkaTemplate<String, String>>()
        val consumer = AuthCharacterFetchConsumer(
            nexonAuthClient = client,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            responseTopic = "auth-response",
            executor = java.util.concurrent.Executor(Runnable::run),
        )
        whenever(client.getCharacterList(SECRET)).thenReturn(Optional.empty())
        val configured = ExternalApiSubscriptions(
            urgentConsumer = urgentConsumer,
            authConsumer = consumer,
            authSanitizer = AuthRequestDltSanitizer(objectMapper),
            urgentTopic = "urgent-character-request",
            urgentGroupId = "external-api-urgent-processor",
            authTopic = "auth-character-fetch-request",
            authGroupId = "module-external-api-auth-consumer",
        )
        val payload = objectMapper.writeValueAsString(
            CharacterFetchRequest(
                eventId = "event-1",
                userIgn = "TestIgn",
                apiKey = SECRET,
                requestedAt = Instant.EPOCH,
            ),
        )
        return AuthFixture(configured, kafkaTemplate, payload)
    }

    private fun context(key: String? = "key"): DeliveryContext = DeliveryContext(
        listenerId = "external-api-test",
        topic = "source-topic",
        partition = 1,
        offset = 2,
        timestamp = Instant.EPOCH,
        key = key,
        deliveryAttempt = 1,
    )

    private data class AuthFixture(
        val subscriptions: ExternalApiSubscriptions,
        val kafkaTemplate: KafkaTemplate<String, String>,
        val payload: String,
    )

    private companion object {
        private const val SECRET = "auth-secret-value"
    }
}

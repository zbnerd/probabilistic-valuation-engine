package maple.externalapi.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.nexon.client.byok.ByokNexonClient
import maple.nexon.client.byok.NexonAccount
import maple.nexon.client.byok.NexonCharacter
import maple.nexon.client.byok.NexonCharacterList
import maple.nexon.client.failure.DecodeFailure
import maple.nexon.client.failure.InvalidCredential
import maple.nexon.client.failure.InvalidRequest
import maple.nexon.client.failure.NotFound
import maple.nexon.client.failure.RateLimited
import maple.nexon.client.failure.ResponseTooLarge
import maple.nexon.client.failure.Timeout
import maple.nexon.client.failure.TimeoutKind
import maple.nexon.client.failure.UpstreamUnavailable
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

class AuthCharacterFetchHandlerTest {
    private val client = mock<ByokNexonClient>()
    private val kafkaTemplate = mock<KafkaTemplate<String, String>>()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val handler = AuthCharacterFetchHandler(
        byokNexonClient = client,
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        responseTopic = RESPONSE_TOPIC,
    )

    @Test
    fun `success maps neutral model and waits for response send before delivery success`() {
        whenever(client.getCharacterList(KEY)).thenReturn(
            CompletableFuture.completedFuture(
                NexonCharacterList(
                    listOf(
                        NexonAccount(
                            "account-1",
                            listOf(
                                NexonCharacter("ocid-1", "Hero", null, null, 280),
                                NexonCharacter("", "BlankOcid", null, null, 0),
                                NexonCharacter("ocid-ignored", " ", null, null, 0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val send = CompletableFuture<SendResult<String, String>>()
        whenever(kafkaTemplate.send(eq(RESPONSE_TOPIC), eq(EVENT_ID), any())).thenReturn(send)

        val delivery = handler.handle(payload(), "source-key").toCompletableFuture()

        assertThat(delivery).isNotDone()
        val json = argumentCaptor<String>()
        verify(kafkaTemplate).send(eq(RESPONSE_TOPIC), eq(EVENT_ID), json.capture())
        val response = objectMapper.readValue(json.firstValue, CharacterFetchResponse::class.java)
        assertThat(response.eventId).isEqualTo(EVENT_ID)
        assertThat(response.accountId).isEqualTo("account-1")
        assertThat(response.success).isTrue()
        assertThat(response.characterOcidMap).containsExactlyEntriesOf(mapOf("Hero" to "ocid-1"))

        send.complete(null)
        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
    }

    @Test
    fun `valid empty response is successful and distinct from credential or not-found`() {
        whenever(client.getCharacterList(KEY)).thenReturn(
            CompletableFuture.completedFuture(NexonCharacterList(emptyList())),
        )
        whenever(kafkaTemplate.send(eq(RESPONSE_TOPIC), eq(EVENT_ID), any()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val outcome = handler.handle(payload(), EVENT_ID).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
        val json = argumentCaptor<String>()
        verify(kafkaTemplate).send(eq(RESPONSE_TOPIC), eq(EVENT_ID), json.capture())
        val response = objectMapper.readValue(json.firstValue, CharacterFetchResponse::class.java)
        assertThat(response.success).isTrue()
        assertThat(response.accountId).isNull()
        assertThat(response.characterOcidMap).isEmpty()
    }

    @Test
    fun `credential and not-found publish distinct terminal responses before success`() {
        assertTerminalResponse(
            InvalidCredential(REQUEST, 401, "OPENAPI00001"),
            "Invalid API key or Nexon API error (OPENAPI00004)",
        )
        clearInvocations(kafkaTemplate, client)
        assertTerminalResponse(NotFound(REQUEST, 400, "OPENAPI00004"), "No accessible characters found")
    }

    @Test
    fun `transient cap and decode failures return Retryable without terminal response`() {
        val failures = listOf(
            RateLimited(REQUEST, 429, "OPENAPI00007", null),
            Timeout(REQUEST, TimeoutKind.CALL),
            UpstreamUnavailable(REQUEST, 503, "UPSTREAM"),
            ResponseTooLarge(REQUEST),
            DecodeFailure(REQUEST),
        )
        failures.forEach { failure ->
            clearInvocations(kafkaTemplate, client)
            whenever(client.getCharacterList(KEY)).thenReturn(CompletableFuture.failedFuture(failure))

            val outcome = handler.handle(payload(), EVENT_ID).toCompletableFuture().resultNow()

            assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
            verifyNoInteractions(kafkaTemplate)
        }
    }

    @Test
    fun `invalid source or typed invalid request returns InvalidMessage`() {
        assertThat(handler.handle("not-json", EVENT_ID).toCompletableFuture().resultNow())
            .isEqualTo(DeliveryOutcome.InvalidMessage("INVALID_MESSAGE"))
        whenever(client.getCharacterList(KEY)).thenReturn(
            CompletableFuture.failedFuture(InvalidRequest(REQUEST, 400, "OPENAPI99999")),
        )

        assertThat(handler.handle(payload(), EVENT_ID).toCompletableFuture().resultNow())
            .isEqualTo(DeliveryOutcome.InvalidMessage("INVALID_NEXON_REQUEST"))
        verifyNoInteractions(kafkaTemplate)
    }

    @Test
    fun `response send failure is Retryable`() {
        val failure = IllegalStateException("send failed")
        whenever(client.getCharacterList(KEY)).thenReturn(
            CompletableFuture.completedFuture(NexonCharacterList(emptyList())),
        )
        whenever(kafkaTemplate.send(eq(RESPONSE_TOPIC), eq(EVENT_ID), any()))
            .thenReturn(CompletableFuture.failedFuture(failure))

        val outcome = handler.handle(payload(), EVENT_ID).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
    }

    private fun assertTerminalResponse(failure: Throwable, expectedMessage: String) {
        val send = CompletableFuture<SendResult<String, String>>()
        whenever(client.getCharacterList(KEY)).thenReturn(CompletableFuture.failedFuture(failure))
        whenever(kafkaTemplate.send(eq(RESPONSE_TOPIC), eq(EVENT_ID), any())).thenReturn(send)

        val delivery = handler.handle(payload(), EVENT_ID).toCompletableFuture()
        assertThat(delivery).isNotDone()
        val json = argumentCaptor<String>()
        verify(kafkaTemplate).send(eq(RESPONSE_TOPIC), eq(EVENT_ID), json.capture())
        val response = objectMapper.readValue(json.firstValue, CharacterFetchResponse::class.java)
        assertThat(response.success).isFalse()
        assertThat(response.errorMessage).isEqualTo(expectedMessage)
        send.complete(null)
        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
    }

    private fun payload(): String = objectMapper.writeValueAsString(
        CharacterFetchRequest(
            eventId = EVENT_ID,
            userIgn = "TestIgn",
            apiKey = KEY,
            requestedAt = Instant.EPOCH,
        ),
    )

    private companion object {
        private const val EVENT_ID = "event-1"
        private const val KEY = "synthetic-auth-key"
        private const val RESPONSE_TOPIC = "auth-response"
        private val REQUEST = NexonRequest(
            NexonEndpointPurpose.CHARACTER_LIST,
            "/maplestory/v1/character/list",
            emptyMap(),
            "/maplestory/v1/character/list",
        )
    }
}

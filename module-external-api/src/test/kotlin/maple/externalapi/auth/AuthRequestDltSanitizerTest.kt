package maple.externalapi.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.pipeline.messaging.contract.DeliveryContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthRequestDltSanitizerTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val sanitizer = AuthRequestDltSanitizer(objectMapper)
    private val secret = "literal-secret-api-key-94821"

    @Test
    fun `valid request retains event identity and digest but no credential material`() {
        val payload = objectMapper.writeValueAsString(
            CharacterFetchRequest(
                eventId = "event-1",
                userIgn = "SensitiveIgn",
                apiKey = secret,
                requestedAt = Instant.parse("2026-07-20T00:00:00Z"),
            ),
        )

        val sanitized = sanitizer.sanitize(secret, payload, context())
        val document = objectMapper.readTree(sanitized.value)
        val allMaterial = buildString {
            append(sanitized.key)
            append(sanitized.value)
            sanitized.extraHeaders.forEach { (name, value) -> append(name).append(value.decodeToString()) }
        }

        assertThat(sanitized.key).isEqualTo("event-1")
        assertThat(document.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "eventId",
            "topic",
            "partition",
            "offset",
            "payloadSha256",
            "payloadBytes",
        )
        assertThat(document["payloadSha256"].asText()).matches("[0-9a-f]{64}")
        assertThat(document["payloadBytes"].asInt()).isEqualTo(payload.toByteArray().size)
        assertThat(allMaterial).doesNotContain(secret).doesNotContain("SensitiveIgn")
        assertThat(sanitized.extraHeaders).isEmpty()
    }

    @Test
    fun `malformed request retains only digest length and safe record metadata`() {
        val payload = "not-json-$secret"

        val sanitized = sanitizer.sanitize(secret, payload, context())
        val document = objectMapper.readTree(sanitized.value)
        val allMaterial = "${sanitized.key}|${sanitized.value}|${sanitized.extraHeaders}"

        assertThat(sanitized.key).isNull()
        assertThat(document.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "topic",
            "partition",
            "offset",
            "payloadSha256",
            "payloadBytes",
        )
        assertThat(allMaterial).doesNotContain(secret)
        assertThat(document["payloadBytes"].asInt()).isEqualTo(payload.toByteArray().size)
        assertThat(sanitized.extraHeaders).isEmpty()
    }

    private fun context(): DeliveryContext = DeliveryContext(
        listenerId = "external-api-auth-character-fetch",
        topic = "auth-character-fetch-request",
        partition = 3,
        offset = 42,
        timestamp = Instant.EPOCH,
        key = secret,
        deliveryAttempt = 1,
    )
}

package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.dlt.DltPayload
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.springframework.stereotype.Component

@Component
class AuthRequestDltSanitizer(
    private val objectMapper: ObjectMapper,
) : DltRecordSanitizer {
    override fun sanitize(key: String?, value: String, context: DeliveryContext): DltPayload {
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        val safeFields = linkedMapOf<String, Any>(
            "topic" to context.topic,
            "partition" to context.partition,
            "offset" to context.offset,
            "payloadSha256" to valueBytes.sha256(),
            "payloadBytes" to valueBytes.size,
        )
        val eventId = runCatching {
            objectMapper.readValue(value, CharacterFetchRequest::class.java).eventId
        }.getOrNull()
        eventId?.let { safeFields["eventId"] = it }
        return DltPayload(
            key = eventId,
            value = objectMapper.writeValueAsString(safeFields),
            extraHeaders = emptyMap(),
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

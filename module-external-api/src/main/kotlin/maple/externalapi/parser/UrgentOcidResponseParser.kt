package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.urgent.UrgentCharacterRequest
import org.springframework.stereotype.Component

/**
 * Centralizes JSON parsing for the urgent request pipeline. Extracts the OCID
 * field from a Nexon OCID-lookup HTTP response body and decodes the incoming
 * Kafka request message. The urgent consumer delegates JSON parsing to this
 * class so it never touches `ObjectMapper` directly.
 */
@Component
class UrgentOcidResponseParser(
    private val objectMapper: ObjectMapper,
) {
    /** Returns the OCID string, or null if the field is missing/blank. */
    fun extractOcid(responseBody: String): String? {
        val root = objectMapper.readTree(responseBody)
        val ocid = root.path("ocid").asText()
        return ocid.takeIf { it.isNotBlank() }
    }

    /** Byte-array overload for callers that received a raw HTTP body. */
    fun extractOcid(responseBody: ByteArray): String? =
        extractOcid(responseBody.toString(Charsets.UTF_8))

    /** Decodes an urgent request Kafka message body. */
    fun parseRequest(message: String): UrgentCharacterRequest =
        objectMapper.readValue(message, UrgentCharacterRequest::class.java)
}

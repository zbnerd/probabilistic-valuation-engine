package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Parses Nexon OCID-lookup HTTP responses and serializes OCID mapping records
 * for the snapshot chunk writer. The owning phase (`OcidLookupPhase`) does
 * not import `ObjectMapper`; all JSON access lives here.
 */
@Component
class OcidResponseParser(
    private val objectMapper: ObjectMapper,
) {
    /** Returns the OCID string, or null if the field is missing/blank. */
    fun extractOcid(responseBody: String): String? {
        val root = objectMapper.readTree(responseBody)
        val ocid = root.path("ocid").asText()
        return ocid.takeIf { it.isNotBlank() }
    }

    /** Byte-array overload for callers that received a raw HTTP body. */
    fun extractOcid(responseBody: ByteArray): String? = extractOcid(responseBody.toString(Charsets.UTF_8))

    /**
     * Serialize a single (userIgn, ocid) mapping as a JSON line. Matches the
     * output shape that downstream consumers (e.g. `CharacterNameReader`
     * consumers) expect: `{"userIgn":"...","ocid":"..."}`.
     */
    fun serializeMapping(userIgn: String, ocid: String): ByteArray = objectMapper.writeValueAsBytes(mapOf("userIgn" to userIgn, "ocid" to ocid))
}

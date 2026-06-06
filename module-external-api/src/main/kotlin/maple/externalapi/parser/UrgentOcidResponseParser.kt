package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Extracts the OCID field from a Nexon OCID-lookup HTTP response body. The
 * urgent consumer delegates JSON parsing to this class so it never touches
 * `ObjectMapper` directly.
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
}

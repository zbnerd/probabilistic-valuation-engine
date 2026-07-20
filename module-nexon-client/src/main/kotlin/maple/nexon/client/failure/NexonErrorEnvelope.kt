package maple.nexon.client.failure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class NexonErrorEnvelope(
    val error: NexonError? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NexonError(
        val name: String? = null,
        val message: String? = null,
    )
}

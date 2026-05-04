package maple.externalapi.domain

import java.time.Instant

data class ExternalApiFetchResult(
    val jobId: String,
    val endpoint: ExternalApiEndpoint,
    val requestKey: String,
    val payloadRef: ExternalApiPayloadRef?,
    val success: Boolean,
    val errorMessage: String? = null,
    val fetchedAt: Instant = Instant.now(),
)

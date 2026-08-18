package maple.nexon.client.failure

import java.time.Duration
import maple.nexon.client.model.NexonEndpointPurpose
import maple.nexon.client.model.NexonRequest

sealed class NexonFailure(
    message: String,
    val status: Int?,
    val nexonCode: String?,
    val endpointPurpose: NexonEndpointPurpose,
    val endpointTemplate: String,
) : RuntimeException(message, null) {
    init {
        require(nexonCode == null || SAFE_CODE.matches(nexonCode)) { "Nexon error code must be sanitized" }
        require(endpointTemplate.startsWith('/') && '?' !in endpointTemplate && '#' !in endpointTemplate) {
            "Nexon endpoint template must be sanitized"
        }
    }

    private companion object {
        private val SAFE_CODE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

class InvalidCredential(
    request: NexonRequest,
    status: Int,
    nexonCode: String?,
) : NexonFailure(
    message = "Nexon credential rejected",
    status = status,
    nexonCode = nexonCode,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class NotFound(
    request: NexonRequest,
    status: Int,
    nexonCode: String?,
) : NexonFailure(
    message = "Nexon resource not found",
    status = status,
    nexonCode = nexonCode,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class InvalidRequest(
    request: NexonRequest,
    status: Int,
    nexonCode: String?,
) : NexonFailure(
    message = "Nexon request rejected",
    status = status,
    nexonCode = nexonCode,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class RateLimited(
    request: NexonRequest,
    status: Int,
    nexonCode: String?,
    val retryAfter: Duration?,
) : NexonFailure(
    message = "Nexon request rate limited",
    status = status,
    nexonCode = nexonCode,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

enum class TimeoutKind {
    CONNECT,
    RESPONSE,
    CALL,
    ACQUIRE,
}

class Timeout(
    request: NexonRequest,
    val kind: TimeoutKind,
) : NexonFailure(
    message = "Nexon request timed out",
    status = null,
    nexonCode = null,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class UpstreamUnavailable(
    request: NexonRequest,
    status: Int? = null,
    nexonCode: String? = null,
) : NexonFailure(
    message = "Nexon upstream unavailable",
    status = status,
    nexonCode = nexonCode,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class ResponseTooLarge(
    request: NexonRequest,
) : NexonFailure(
    message = "Nexon response exceeded configured limit",
    status = null,
    nexonCode = null,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

class DecodeFailure(
    request: NexonRequest,
) : NexonFailure(
    message = "Nexon response could not be decoded",
    status = null,
    nexonCode = null,
    endpointPurpose = request.purpose,
    endpointTemplate = request.endpointTemplate,
)

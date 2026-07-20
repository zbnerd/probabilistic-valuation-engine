package maple.nexon.client.failure

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import maple.nexon.client.model.NexonRequest
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.web.reactive.function.client.WebClientResponseException

class NexonFailureClassifier(
    private val objectMapper: ObjectMapper,
) {
    fun classifyHttp(
        request: NexonRequest,
        status: Int,
        errorBody: ByteArray,
        retryAfter: Duration?,
    ): NexonFailure {
        val nexonCode = readSanitizedCode(errorBody)
        return when {
            status == 401 || status == 403 -> InvalidCredential(request, status, nexonCode)
            nexonCode == NOT_FOUND_CODE -> NotFound(request, status, nexonCode)
            status == 429 -> RateLimited(request, status, nexonCode, retryAfter)
            status in 400..499 -> InvalidRequest(request, status, nexonCode)
            status >= 500 -> UpstreamUnavailable(request, status, nexonCode)
            else -> DecodeFailure(request)
        }
    }

    fun classifyTransport(request: NexonRequest, failure: Throwable): NexonFailure {
        val unwrapped = unwrap(failure)
        if (unwrapped is NexonFailure) {
            return unwrapped
        }
        if (unwrapped is WebClientResponseException) {
            return classifyHttp(
                request = request,
                status = unwrapped.statusCode.value(),
                errorBody = unwrapped.responseBodyAsByteArray,
                retryAfter = retryAfter(unwrapped),
            )
        }
        return when {
            unwrapped is ConnectTimeoutException -> Timeout(request, TimeoutKind.CONNECT)
            unwrapped is ReadTimeoutException -> Timeout(request, TimeoutKind.RESPONSE)
            unwrapped is TimeoutException -> Timeout(request, TimeoutKind.CALL)
            isPoolAcquireTimeout(unwrapped) -> Timeout(request, TimeoutKind.ACQUIRE)
            unwrapped is DataBufferLimitException -> ResponseTooLarge(request)
            unwrapped is ConnectException -> UpstreamUnavailable(request)
            else -> UpstreamUnavailable(request)
        }
    }

    fun responseTooLarge(request: NexonRequest): NexonFailure = ResponseTooLarge(request)

    fun decodeFailure(request: NexonRequest): NexonFailure = DecodeFailure(request)

    fun timeout(request: NexonRequest, kind: TimeoutKind): NexonFailure = Timeout(request, kind)

    private fun readSanitizedCode(errorBody: ByteArray): String? = runCatching {
        objectMapper.readValue(errorBody.copyOf(MAX_ERROR_BODY_BYTES.coerceAtMost(errorBody.size)), NexonErrorEnvelope::class.java)
            .error
            ?.name
            ?.takeIf(SAFE_CODE::matches)
    }.getOrNull()

    private fun retryAfter(failure: WebClientResponseException): Duration? = failure.headers
        .getFirst("Retry-After")
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let(Duration::ofSeconds)

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private fun isPoolAcquireTimeout(failure: Throwable): Boolean = failure.javaClass.simpleName.contains("PoolAcquireTimeout", ignoreCase = true) ||
        failure.javaClass.simpleName.contains("PoolAcquirePendingLimit", ignoreCase = true)

    private companion object {
        private const val NOT_FOUND_CODE = "OPENAPI00004"
        private const val MAX_ERROR_BODY_BYTES = 8 * 1024
        private val SAFE_CODE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

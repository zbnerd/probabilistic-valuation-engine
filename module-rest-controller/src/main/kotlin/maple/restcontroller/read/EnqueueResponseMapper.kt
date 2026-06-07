package maple.restcontroller.read

import org.springframework.http.ResponseEntity

/**
 * Maps an [EnqueueResult] (or the timeout path for an in-flight request) to
 * the HTTP response the caller should apply to a deferred result.
 *
 * This is the only place where the HTTP status code for the enqueue step
 * (503 Service Unavailable on buffer full, 202 Accepted on timeout) is
 * decided. Keeping the mapping in a single object lets the controller
 * produce HTTP responses without the service layer constructing
 * `ResponseEntity` instances inline.
 */
object EnqueueResponseMapper {

    /** Buffer full / system cannot accept the request. */
    fun toServiceUnavailableResponse(result: EnqueueResult.ServiceUnavailable): ResponseEntity<*> =
        ResponseEntity.status(SERVICE_UNAVAILABLE_STATUS)
            .header("Retry-After", RETRY_AFTER_ONE_SECOND)
            .build<Any>()

    /** Deferred timed out before the batch scheduler could resolve it. */
    fun toTimeoutResponse(
        userIgn: String,
        presetNo: Int,
        status: UrgentReadStatusResponse,
        retryAfterSeconds: Long,
    ): ResponseEntity<*> = ResponseEntity.accepted()
        .header("Location", "/api/v6/characters/$userIgn/status?presetNo=$presetNo")
        .header("Retry-After", retryAfterSeconds.toString())
        .body(status)

    private const val SERVICE_UNAVAILABLE_STATUS: Int = 503
    private const val RETRY_AFTER_ONE_SECOND: String = "1"
}

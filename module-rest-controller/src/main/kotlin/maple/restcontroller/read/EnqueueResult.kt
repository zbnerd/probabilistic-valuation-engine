package maple.restcontroller.read

/**
 * Typed result from [ExpectationReadFacade.enqueue].
 *
 * Replaces inline `ResponseEntity` construction so the HTTP-shape decision lives
 * in the controller layer (via [EnqueueResponseMapper]) and the service returns
 * data only.
 */
sealed interface EnqueueResult {
    /** Request was buffered (or will be picked up by the batch scheduler). */
    data object Queued : EnqueueResult

    /** Dedup hit — a peer request is already in flight. */
    data object AlreadyInFlight : EnqueueResult

    /** Request was rejected (e.g. buffer full). Caller should fail the deferred. */
    data class ServiceUnavailable(val reason: String) : EnqueueResult
}

package maple.restcontroller.read

/**
 * Typed result from [BatchResolver.resolveBatch].
 *
 * Replaces the previous `Int` return + inline `ResponseEntity` construction so
 * the HTTP-shape decision lives in the controller layer (via
 * [ExpectationReadResponseMapper]) and the service returns data only.
 *
 * - [AllResolved]: every request in the batch produced a value the caller can map
 * - [PartiallyResolved]: some requests were left pending (urgent pipeline triggered
 *   or no urgent publisher); the caller should let those deferreds time out to 202
 */
sealed interface BatchResolveResult {
    val resolved: List<ResolvedItem>
    val pendingCount: Int
    val resolvedCount: Int get() = resolved.size

    data class AllResolved(override val resolved: List<ResolvedItem>) : BatchResolveResult {
        override val pendingCount: Int = 0
    }

    data class PartiallyResolved(
        override val resolved: List<ResolvedItem>,
        override val pendingCount: Int,
    ) : BatchResolveResult
}

/**
 * One resolved request within a [BatchResolveResult].
 *
 * The HTTP status (200 vs 404) and body (response payload vs empty) are decided
 * by the controller-side mapper, not here.
 */
sealed interface ResolvedItem {
    val userIgn: String
    val presetNo: Int

    /** Cache or DB hit — maps to `200 OK` with [response] as body. */
    data class Ok(
        override val userIgn: String,
        override val presetNo: Int,
        val response: V6ExpectationResponse,
    ) : ResolvedItem

    /** Negative cache hit — maps to `404 Not Found` with `X-Error-Reason: character-not-found`. */
    data class NotFound(
        override val userIgn: String,
        override val presetNo: Int,
    ) : ResolvedItem
}

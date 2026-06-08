package maple.restcontroller.read

import org.springframework.http.ResponseEntity

/**
 * Maps a [ResolvedItem] produced by [BatchResolver] to the HTTP response the
 * caller should apply to a deferred result.
 *
 * This is the only place where the HTTP status code for batch-resolved reads
 * (200 OK / 404 Not Found) is decided. Keeping the mapping in a single
 * object lets the controller and the batch scheduler share one source of
 * truth and keeps `ResponseEntity` construction out of the service layer.
 */
object ExpectationReadResponseMapper {

    fun toResponse(item: ResolvedItem): ResponseEntity<*> = when (item) {
        is ResolvedItem.Ok -> ResponseEntity.ok(item.response)
        is ResolvedItem.NotFound -> ResponseEntity.status(NOT_FOUND_STATUS)
            .header("X-Error-Reason", CHARACTER_NOT_FOUND_REASON)
            .build<Any>()
    }

    private const val NOT_FOUND_STATUS: Int = 404
    private const val CHARACTER_NOT_FOUND_REASON: String = "character-not-found"
}

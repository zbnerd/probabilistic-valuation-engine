package maple.restcontroller.popular.port.out

/**
 * Read-side projection of a Redis ZSET tuple. Returned by the port so that
 * the service layer never depends on Spring's `ZSetOperations` type.
 */
data class PopularCharacterScoreEntry(
    val value: String?,
    val score: Double?,
)

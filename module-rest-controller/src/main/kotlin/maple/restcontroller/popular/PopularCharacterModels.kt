package maple.restcontroller.popular

data class PopularCharacterEntry(
    val rank: Int,
    val userIgn: String,
    val requestCount: Long,
)

data class PopularCharacterResponse(
    val windowHours: Int,
    val source: PopularCharacterSource,
    val degraded: Boolean,
    val characters: List<PopularCharacterEntry>,
)

enum class PopularCharacterSource {
    REDIS,
    DEGRADED,
}

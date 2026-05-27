package maple.expectation.core.auth.event

import java.time.Instant
import java.util.UUID

data class CharacterFetchRequest(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String = "AUTH_CHARACTER_FETCH_REQUESTED",
    val userIgn: String,
    val apiKey: String,
    val requestedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = eventId
}

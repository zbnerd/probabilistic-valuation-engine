package maple.expectation.core.auth.event

import java.time.Instant

data class CharacterFetchResponse(
    val eventId: String,
    val eventType: String = "AUTH_CHARACTER_FETCH_COMPLETED",
    val accountId: String? = null,
    val success: Boolean,
    val errorMessage: String? = null,
    val characterOcidMap: Map<String, String> = emptyMap(),
    val failedIgn: List<String> = emptyList(),
    val completedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = eventId
}

package maple.restcontroller.urgent

import java.time.Instant
import java.util.UUID

data class UrgentCharacterRequest(
    val eventId: String = UUID.randomUUID().toString(),
    val userIgn: String,
    val presetNo: Int = 1,
    val requestedAt: Instant = Instant.now()
)

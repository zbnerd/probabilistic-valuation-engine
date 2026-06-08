package maple.restcontroller.read

import java.util.UUID

data class ReadRequest(
    val requestId: UUID = UUID.randomUUID(),
    val userIgn: String,
    val presetNo: Int = 1,
)

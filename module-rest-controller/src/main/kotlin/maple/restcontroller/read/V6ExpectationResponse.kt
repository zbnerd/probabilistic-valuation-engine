package maple.restcontroller.read

import java.math.BigDecimal
import java.time.Instant

data class V6ExpectationResponse(
    val userIgn: String,
    val presetNo: Int,
    val totalCost: BigDecimal,
    val equipmentCount: Int,
    val equipment: List<Map<String, Any?>>,
    val calculatedAt: Instant,
)

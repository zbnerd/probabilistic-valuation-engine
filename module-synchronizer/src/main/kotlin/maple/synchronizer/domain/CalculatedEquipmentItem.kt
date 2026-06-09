package maple.synchronizer.domain

import java.math.BigDecimal
import java.time.Instant

data class EquipmentReadDocument(
    val ocid: String,
    val presetNo: Int,
    val userIgn: String? = null,
    val summary: EquipmentSummary,
    val equipment: List<Map<String, Any?>>,
    val metadata: EquipmentReadMetadata,
)

data class EquipmentSummary(
    val totalCost: BigDecimal,
    val equipmentCount: Int,
)

data class EquipmentReadMetadata(
    val sourceRunId: String,
    val sourceChunkId: String,
    val calculatedAt: Instant,
)

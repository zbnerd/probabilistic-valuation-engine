package maple.synchronizer.domain

import java.math.BigDecimal
import java.time.Instant

data class CalculatedEquipmentItem(
    val ocid: String,
    val presetNo: Int,
    val itemName: String,
    val itemLevel: Int,
    val itemPart: String,
    val itemEquipmentPart: String?,
    val potentialGrade: String?,
    val potentialOptions: List<String>?,
    val additionalGrade: String?,
    val additionalOptions: List<String>?,
    val currentStar: Int,
    val targetStar: Int,
    val status: String,
    val totalCost: BigDecimal,
    val blackCubeCost: BigDecimal,
    val additionalCubeCost: BigDecimal,
    val starforceCost: BigDecimal,
    val errorMessage: String?,
)

data class GroupedEquipmentResult(
    val readKey: String,
    val ocid: String,
    val presetNo: Int,
    val userIgn: String? = null,
    val items: List<CalculatedEquipmentItem>,
)

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

package maple.expectation.core.model.chunk

import java.math.BigDecimal

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

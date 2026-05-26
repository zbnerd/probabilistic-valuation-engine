package maple.calculator.model

data class CalculationResult(
    val ocid: String,
    val presetNo: Int,
    val itemName: String,
    val itemLevel: Int,
    val itemPart: String?,
    val itemEquipmentPart: String?,
    val potentialGrade: String?,
    val potentialOptions: List<String?>,
    val additionalGrade: String?,
    val additionalOptions: List<String>,
    val currentStar: Int,
    val targetStar: Int,
    val status: String,
    val totalCost: Double?,
    val blackCubeCost: Double?,
    val additionalCubeCost: Double?,
    val starforceCost: Double?,
    val errorMessage: String? = null,
)

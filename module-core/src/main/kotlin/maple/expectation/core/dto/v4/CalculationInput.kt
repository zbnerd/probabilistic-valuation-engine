package maple.expectation.core.dto.v4

data class CalculationInput(
    val schemaVersion: Int = 1,
    val jobId: String,
    val userIgn: String,
    val characterClass: String,
    val presetNo: Int,
    val items: List<EquipmentItem>,
)

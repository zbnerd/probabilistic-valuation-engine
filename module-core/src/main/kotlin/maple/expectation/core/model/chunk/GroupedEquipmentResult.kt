package maple.expectation.core.model.chunk

data class GroupedEquipmentResult(
    val readKey: String,
    val ocid: String,
    val presetNo: Int,
    val userIgn: String? = null,
    val items: List<CalculatedEquipmentItem>,
)

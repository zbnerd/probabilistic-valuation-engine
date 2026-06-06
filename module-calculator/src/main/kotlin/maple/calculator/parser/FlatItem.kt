package maple.calculator.parser

import maple.expectation.core.dto.v4.EquipmentItem

data class FlatItem(
    val ocid: String,
    val presetNo: Int,
    val item: EquipmentItem,
)

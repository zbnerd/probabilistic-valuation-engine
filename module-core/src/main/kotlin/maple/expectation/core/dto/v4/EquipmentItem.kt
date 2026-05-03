package maple.expectation.core.dto.v4

data class EquipmentItem(
    val part: EquipmentSlot,
    val equipmentPart: EquipmentPart,
    val itemName: String,
    val level: Int,
    val potential: PotentialLines?,
    val additionalPotential: PotentialLines?,
    val starforce: Int,
    val starforceScrollFlag: StarforceScrollFlag,
    val addOption: AddOption,
    val baseAttackPower: Int,
    val baseMagicPower: Int,
)

package maple.expectation.core.dto.v4

import maple.expectation.core.dto.cube.CubeCalculationInput

object EquipmentItemConverter {

    fun toCubeInput(item: EquipmentItem): CubeCalculationInput = CubeCalculationInput(
        level = item.level,
        part = item.part.koreanName,
        grade = item.potential?.grade?.koreanName,
        options = item.potential?.asList()?.filterNotNull()?.toMutableList() ?: mutableListOf(),
        itemName = item.itemName,
        itemIcon = "",
        itemEquipmentPart = item.equipmentPart.koreanName,
        additionalGrade = item.additionalPotential?.grade?.koreanName,
        additionalOptions = item.additionalPotential?.asList()?.filterNotNull()?.toMutableList() ?: mutableListOf(),
        starforce = item.starforce,
        starforceScrollFlag = item.starforceScrollFlag.koreanValue,
        addOptionStr = item.addOption.str,
        addOptionDex = item.addOption.dex,
        addOptionInt = item.addOption.`int`,
        addOptionLuk = item.addOption.luk,
        addOptionMaxHp = item.addOption.maxHp,
        addOptionAllStat = item.addOption.allStat,
        addOptionAtt = item.addOption.attackPower,
        addOptionMag = item.addOption.magicPower,
        addOptionBossDmg = item.addOption.bossDamage,
        addOptionDmg = item.addOption.damage,
        baseAttackPower = item.baseAttackPower,
        baseMagicPower = item.baseMagicPower,
    )
}

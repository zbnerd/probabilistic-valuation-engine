package maple.expectation.infrastructure.converter

import maple.expectation.core.dto.v4.*
import maple.expectation.core.domain.model.PotentialGrade
import org.springframework.stereotype.Component

@Component
class EquipmentResponseToCalculationInputConverter {

    fun convertItem(item: Map<*, *>): EquipmentItem {
        val baseOption = item["item_base_option"] as? Map<*, *>
        val addOption = item["item_add_option"] as? Map<*, *>

        return EquipmentItem(
            part = EquipmentSlot.fromKorean(item["item_equipment_slot"] as? String ?: ""),
            equipmentPart = EquipmentPart.fromKorean(item["item_equipment_part"] as? String ?: ""),
            itemName = item["item_name"] as? String ?: "",
            level = intStr(baseOption?.get("base_equipment_level")),
            potential = buildPotentialLines(item, "potential_option_grade", "potential_option_"),
            additionalPotential = buildPotentialLines(item, "additional_potential_option_grade", "additional_potential_option_"),
            starforce = intStr(item["starforce"]),
            starforceScrollFlag = StarforceScrollFlag.fromKorean(item["starforce_scroll_flag"] as? String),
            addOption = AddOption(
                str = intStr(addOption?.get("str")),
                dex = intStr(addOption?.get("dex")),
                int = intStr(addOption?.get("int")),
                luk = intStr(addOption?.get("luk")),
                maxHp = intStr(addOption?.get("max_hp")),
                allStat = intStr(addOption?.get("all_stat")),
                attackPower = intStr(addOption?.get("attack_power")),
                magicPower = intStr(addOption?.get("magic_power")),
                bossDamage = intStr(addOption?.get("boss_damage")),
                damage = intStr(addOption?.get("damage"))
            ),
            baseAttackPower = intStr(baseOption?.get("attack_power")),
            baseMagicPower = intStr(baseOption?.get("magic_power"))
        )
    }

    private fun intStr(value: Any?): Int = value?.toString()?.toIntOrNull() ?: 0

    private fun buildPotentialLines(item: Map<*, *>, gradeKey: String, optionPrefix: String): PotentialLines? {
        val gradeStr = item[gradeKey] as? String ?: return null
        val grade = PotentialGrade.entries.find { it.koreanName == gradeStr } ?: return null
        return PotentialLines(
            grade = grade,
            line1 = item["${optionPrefix}1"] as? String,
            line2 = item["${optionPrefix}2"] as? String,
            line3 = item["${optionPrefix}3"] as? String
        )
    }
}

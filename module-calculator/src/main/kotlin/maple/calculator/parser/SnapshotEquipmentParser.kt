package maple.calculator.parser

import com.fasterxml.jackson.databind.JsonNode
import maple.expectation.core.domain.model.PotentialGrade
import maple.expectation.core.dto.v4.*
import org.springframework.stereotype.Component

@Component
class SnapshotEquipmentParser {

    fun parseAllPresets(body: JsonNode): Map<Int, List<EquipmentItem>> {
        val result = mutableMapOf<Int, List<EquipmentItem>>()
        for (presetNo in 1..3) {
            val preset = body.path("item_equipment_preset_$presetNo")
            if (preset.isArray) {
                result[presetNo] = preset.map { convertItem(it) }
            }
        }
        return result
    }

    private fun convertItem(item: JsonNode): EquipmentItem {
        val baseOption = item.path("item_base_option")
        val addOption = item.path("item_add_option")

        return EquipmentItem(
            part = EquipmentSlot.fromKorean(item.path("item_equipment_slot").asText("")),
            equipmentPart = EquipmentPart.fromKorean(item.path("item_equipment_part").asText("")),
            itemName = item.path("item_name").asText(""),
            level = intStr(baseOption.path("base_equipment_level")),
            potential = buildPotentialLines(item, "potential_option_grade", "potential_option_"),
            additionalPotential = buildPotentialLines(item, "additional_potential_option_grade", "additional_potential_option_"),
            starforce = intStr(item.path("starforce")),
            starforceScrollFlag = StarforceScrollFlag.fromKorean(item.path("starforce_scroll_flag").asText(null)),
            addOption = AddOption(
                str = intStr(addOption.path("str")),
                dex = intStr(addOption.path("dex")),
                int = intStr(addOption.path("int")),
                luk = intStr(addOption.path("luk")),
                maxHp = intStr(addOption.path("max_hp")),
                allStat = intStr(addOption.path("all_stat")),
                attackPower = intStr(addOption.path("attack_power")),
                magicPower = intStr(addOption.path("magic_power")),
                bossDamage = intStr(addOption.path("boss_damage")),
                damage = intStr(addOption.path("damage")),
            ),
            baseAttackPower = intStr(baseOption.path("attack_power")),
            baseMagicPower = intStr(baseOption.path("magic_power")),
        )
    }

    private fun intStr(node: JsonNode): Int = node.asText().toIntOrNull() ?: 0

    private fun buildPotentialLines(item: JsonNode, gradeKey: String, optionPrefix: String): PotentialLines? {
        val gradeStr = item.path(gradeKey).asText(null) ?: return null
        val grade = PotentialGrade.entries.find { it.koreanName == gradeStr } ?: return null
        return PotentialLines(
            grade = grade,
            line1 = item.path("${optionPrefix}1").asText(null),
            line2 = item.path("${optionPrefix}2").asText(null),
            line3 = item.path("${optionPrefix}3").asText(null),
        )
    }
}

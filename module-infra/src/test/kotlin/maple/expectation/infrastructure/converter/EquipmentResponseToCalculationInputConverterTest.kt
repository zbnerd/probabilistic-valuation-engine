package maple.expectation.infrastructure.converter

import maple.expectation.core.domain.model.PotentialGrade
import maple.expectation.core.dto.v4.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquipmentResponseToCalculationInputConverterTest {

    private val converter = EquipmentResponseToCalculationInputConverter()

    @Test
    fun `converts weapon item with all fields`() {
        val nexonItem = mapOf(
            "item_equipment_slot" to "무기",
            "item_equipment_part" to "무기",
            "item_name" to "아케인셰이드 소드",
            "item_base_option" to mapOf(
                "base_equipment_level" to "200",
                "attack_power" to "293",
                "magic_power" to "0",
            ),
            "potential_option_grade" to "레전드리",
            "potential_option_1" to "공격력 +12%",
            "potential_option_2" to "보스 공격 시 데미지 +40%",
            "potential_option_3" to "크리티컬 데미지 +8%",
            "additional_potential_option_grade" to "유니크",
            "additional_potential_option_1" to "크리티컬 확률 +12%",
            "additional_potential_option_2" to null,
            "additional_potential_option_3" to null,
            "starforce" to "22",
            "starforce_scroll_flag" to "사용",
            "item_add_option" to mapOf(
                "str" to "10", "dex" to "20", "int" to "0", "luk" to "0",
                "max_hp" to "0", "all_stat" to "5",
                "attack_power" to "50", "magic_power" to "0",
                "boss_damage" to "30", "damage" to "0",
            ),
        )

        val item = converter.convertItem(nexonItem)

        assertThat(item.part).isEqualTo(EquipmentSlot.WEAPON)
        assertThat(item.equipmentPart).isEqualTo(EquipmentPart.WEAPON)
        assertThat(item.itemName).isEqualTo("아케인셰이드 소드")
        assertThat(item.level).isEqualTo(200)
        assertThat(item.potential).isNotNull
        assertThat(item.potential!!.grade).isEqualTo(PotentialGrade.LEGENDARY)
        assertThat(item.potential!!.line1).isEqualTo("공격력 +12%")
        assertThat(item.starforce).isEqualTo(22)
        assertThat(item.starforceScrollFlag).isEqualTo(StarforceScrollFlag.USED)
        assertThat(item.baseAttackPower).isEqualTo(293)
        assertThat(item.addOption.attackPower).isEqualTo(50)
    }

    @Test
    fun `null grade produces null potential`() {
        val nexonItem = mapOf(
            "item_equipment_slot" to "모자",
            "item_equipment_part" to "방어구",
            "item_name" to "테스트 모자",
            "item_base_option" to mapOf("base_equipment_level" to "150", "attack_power" to "0", "magic_power" to "0"),
            "potential_option_grade" to null,
            "potential_option_1" to null,
            "potential_option_2" to null,
            "potential_option_3" to null,
            "additional_potential_option_grade" to null,
            "additional_potential_option_1" to null,
            "additional_potential_option_2" to null,
            "additional_potential_option_3" to null,
            "starforce" to "0",
            "starforce_scroll_flag" to null,
            "item_add_option" to mapOf(
                "str" to "0", "dex" to "0", "int" to "0", "luk" to "0",
                "max_hp" to "0", "all_stat" to "0",
                "attack_power" to "0", "magic_power" to "0",
                "boss_damage" to "0", "damage" to "0",
            ),
        )

        val item = converter.convertItem(nexonItem)
        assertThat(item.potential).isNull()
        assertThat(item.additionalPotential).isNull()
    }
}

package maple.expectation.core.dto.v4

import maple.expectation.core.domain.model.PotentialGrade
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EquipmentItemConverterTest {

    @Test
    fun `maps all EquipmentItem fields to CubeCalculationInput`() {
        val item = EquipmentItem(
            part = EquipmentSlot.WEAPON,
            equipmentPart = EquipmentPart.WEAPON,
            itemName = "아케인셰이드 스태프",
            level = 200,
            potential = PotentialLines(
                grade = PotentialGrade.LEGENDARY,
                line1 = "INT +12%",
                line2 = "마력 +9%",
                line3 = "올스탯 +3%"
            ),
            additionalPotential = PotentialLines(
                grade = PotentialGrade.UNIQUE,
                line1 = "INT +9%",
                line2 = "마력 +6%",
                line3 = null
            ),
            starforce = 17,
            starforceScrollFlag = StarforceScrollFlag.USED,
            addOption = AddOption(
                str = 0, dex = 0, `int` = 3, luk = 0,
                maxHp = 0, allStat = 0,
                attackPower = 0, magicPower = 5,
                bossDamage = 0, damage = 0
            ),
            baseAttackPower = 10,
            baseMagicPower = 200
        )

        val result = EquipmentItemConverter.toCubeInput(item)

        assertEquals(200, result.level)
        assertEquals("무기", result.part)
        assertEquals("아케인셰이드 스태프", result.itemName)
        assertEquals("무기", result.itemEquipmentPart)
        assertEquals("레전드리", result.grade)
        assertEquals(listOf("INT +12%", "마력 +9%", "올스탯 +3%"), result.options)
        assertEquals("유니크", result.additionalGrade)
        assertEquals(listOf("INT +9%", "마력 +6%"), result.additionalOptions)
        assertEquals(17, result.starforce)
        assertEquals("사용", result.starforceScrollFlag)
        assertEquals(3, result.addOptionInt)
        assertEquals(5, result.addOptionMag)
        assertEquals(10, result.baseAttackPower)
        assertEquals(200, result.baseMagicPower)
    }

    @Test
    fun `handles null potentials gracefully`() {
        val item = EquipmentItem(
            part = EquipmentSlot.MEDAL,
            equipmentPart = EquipmentPart.ETC,
            itemName = "훈장",
            level = 100,
            potential = null,
            additionalPotential = null,
            starforce = 0,
            starforceScrollFlag = StarforceScrollFlag.NOT_USED,
            addOption = AddOption(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            baseAttackPower = 0,
            baseMagicPower = 0
        )

        val result = EquipmentItemConverter.toCubeInput(item)

        assertNull(result.grade)
        assertTrue(result.options.isEmpty())
        assertNull(result.additionalGrade)
        assertTrue(result.additionalOptions.isEmpty())
        assertEquals(0, result.starforce)
        assertEquals("미사용", result.starforceScrollFlag)
    }
}

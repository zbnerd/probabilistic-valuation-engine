package maple.expectation.core.dto.v4

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.expectation.core.domain.model.PotentialGrade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalculationInputTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    private fun sampleInput() = CalculationInput(
        schemaVersion = 1,
        jobId = "test-job-id",
        userIgn = "testUser",
        characterClass = "hero",
        presetNo = 1,
        items = listOf(
            EquipmentItem(
                part = EquipmentSlot.WEAPON,
                equipmentPart = EquipmentPart.WEAPON,
                itemName = "테스트 무기",
                level = 200,
                potential = PotentialLines(
                    grade = PotentialGrade.LEGENDARY,
                    line1 = "공격력 +12%",
                    line2 = "보스 공격 시 데미지 +40%",
                    line3 = "크리티컬 데미지 +8%"
                ),
                additionalPotential = PotentialLines(
                    grade = PotentialGrade.UNIQUE,
                    line1 = "크리티컬 확률 +12%",
                    line2 = null,
                    line3 = null
                ),
                starforce = 22,
                starforceScrollFlag = StarforceScrollFlag.USED,
                addOption = AddOption(
                    str = 10, dex = 20, int = 0, luk = 0,
                    maxHp = 0, allStat = 5,
                    attackPower = 50, magicPower = 0,
                    bossDamage = 30, damage = 0
                ),
                baseAttackPower = 300,
                baseMagicPower = 0
            )
        )
    )

    @Test
    fun `serialization round-trip preserves all fields`() {
        val original = sampleInput()
        val json = mapper.writeValueAsString(original)
        val deserialized = mapper.readValue(json, CalculationInput::class.java)
        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun `null potential is preserved`() {
        val input = sampleInput().copy(items = listOf(
            sampleInput().items[0].copy(potential = null, additionalPotential = null)
        ))
        val json = mapper.writeValueAsString(input)
        val deserialized = mapper.readValue(json, CalculationInput::class.java)
        assertThat(deserialized.items[0].potential).isNull()
        assertThat(deserialized.items[0].additionalPotential).isNull()
    }
}

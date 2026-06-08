package maple.calculator.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SnapshotChunkParserTest {

    private lateinit var objectMapper: ObjectMapper
    private lateinit var equipmentParser: SnapshotEquipmentParser
    private lateinit var parser: SnapshotChunkParser

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        equipmentParser = mock()
        parser = SnapshotChunkParser(objectMapper, equipmentParser)
    }

    @Test
    fun `parse returns Skipped when status is not SUCCESS`() {
        val line = """{"status":"FAIL","key":"oc1","body":{}}"""

        val outcome = parser.parse(line)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
    }

    @Test
    fun `parse returns Skipped when body is missing or null`() {
        val lineMissing = """{"status":"SUCCESS","key":"oc1"}"""
        val lineNull = """{"status":"SUCCESS","key":"oc1","body":null}"""

        assertThat(parser.parse(lineMissing)).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
        assertThat(parser.parse(lineNull)).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
    }

    @Test
    fun `parse returns Parsed with flat items for all three presets`() {
        val realLine = """{"status":"SUCCESS","key":"oc42","body":{"item_equipment_preset_1":[]}}"""
        whenever(equipmentParser.parseAllPresets(objectMapper.readTree(realLine).path("body"))).thenReturn(
            mapOf(
                1 to listOf(stubItem("a")),
                2 to listOf(stubItem("b")),
                3 to listOf(stubItem("c"), stubItem("d")),
            ),
        )

        val outcome = parser.parse(realLine)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Parsed::class.java)
        val parsed = outcome as SnapshotChunkParser.Outcome.Parsed
        assertThat(parsed.items).hasSize(4)
        assertThat(parsed.items.map { it.ocid }).allMatch { it == "oc42" }
        assertThat(parsed.items.map { it.presetNo }.toSet()).isEqualTo(setOf(1, 2, 3))
    }

    @Test
    fun `parse returns Parsed with empty list when equipmentParser yields no presets`() {
        val realLine = """{"status":"SUCCESS","key":"oc1","body":{}}"""
        whenever(equipmentParser.parseAllPresets(objectMapper.readTree(realLine).path("body"))).thenReturn(emptyMap())

        val outcome = parser.parse(realLine)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Parsed::class.java)
        assertThat((outcome as SnapshotChunkParser.Outcome.Parsed).items).isEmpty()
    }

    private fun stubItem(name: String) = maple.expectation.core.dto.v4.EquipmentItem(
        part = maple.expectation.core.dto.v4.EquipmentSlot.WEAPON,
        equipmentPart = maple.expectation.core.dto.v4.EquipmentPart.WEAPON,
        itemName = name,
        level = 0,
        potential = null,
        additionalPotential = null,
        starforce = 0,
        starforceScrollFlag = maple.expectation.core.dto.v4.StarforceScrollFlag.NOT_USED,
        addOption = maple.expectation.core.dto.v4.AddOption(
            str = 0, dex = 0, int = 0, luk = 0, maxHp = 0, allStat = 0,
            attackPower = 0, magicPower = 0, bossDamage = 0, damage = 0,
        ),
        baseAttackPower = 0,
        baseMagicPower = 0,
    )
}

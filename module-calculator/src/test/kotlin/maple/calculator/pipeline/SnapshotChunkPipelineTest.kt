package maple.calculator.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentSlot
import maple.expectation.core.dto.v4.EquipmentPart
import maple.expectation.core.dto.v4.StarforceScrollFlag
import maple.expectation.core.dto.v4.AddOption

class SnapshotChunkPipelineTest {

    private val properties = PipelineProperties(workerCount = 2, channelCapacity = 16)
    private val pipeline = SnapshotChunkPipeline(properties, kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `run processes all lines and emits one result per FlatItem`() = runTest {
        val source: Flow<String> = flowOf("line-1", "line-2", "line-3")
        val stubItem = stubEquipmentItem()
        val emittedItems = Collections.synchronizedList(mutableListOf<FlatItem>())

        val parse: suspend (String) -> SnapshotChunkParser.Outcome = { line ->
            SnapshotChunkParser.Outcome.Parsed(listOf(FlatItem(ocid = "oc-$line", presetNo = 1, item = stubItem)))
        }
        val calculate: suspend (FlatItem) -> CalculationResult = { flat ->
            emittedItems += flat
            stubCalculationResult(ocid = flat.ocid, presetNo = flat.presetNo, status = "SUCCESS")
        }

        val results = pipeline.run(source, parse, calculate).toList()

        assertThat(results).hasSize(3)
        assertThat(results.map { it.ocid }).containsExactlyInAnyOrder("oc-line-1", "oc-line-2", "oc-line-3")
        assertThat(emittedItems).hasSize(3)
    }

    @Test
    fun `run produces empty result flow when source is empty`() = runTest {
        val source: Flow<String> = flowOf()

        val results = pipeline.run(
            source,
            parse = { SnapshotChunkParser.Outcome.Skipped },
            calculate = { error("should not be called") },
        ).toList()

        assertThat(results).isEmpty()
    }

    @Test
    fun `run does not emit items when parse returns Skipped`() = runTest {
        val source: Flow<String> = flowOf("a", "b", "c")
        var calculateCalls = 0
        val parse: suspend (String) -> SnapshotChunkParser.Outcome = { SnapshotChunkParser.Outcome.Skipped }
        val calculate: suspend (FlatItem) -> CalculationResult = {
            calculateCalls += 1
            error("should not be called when parse skips")
        }

        val results = pipeline.run(source, parse, calculate).toList()

        assertThat(results).isEmpty()
        assertThat(calculateCalls).isEqualTo(0)
    }

    private fun stubEquipmentItem() = EquipmentItem(
        part = EquipmentSlot.WEAPON,
        equipmentPart = EquipmentPart.WEAPON,
        itemName = "test",
        level = 0,
        potential = null,
        additionalPotential = null,
        starforce = 0,
        starforceScrollFlag = StarforceScrollFlag.NOT_USED,
        addOption = AddOption(
            str = 0, dex = 0, int = 0, luk = 0, maxHp = 0, allStat = 0,
            attackPower = 0, magicPower = 0, bossDamage = 0, damage = 0,
        ),
        baseAttackPower = 0,
        baseMagicPower = 0,
    )

    private fun stubCalculationResult(ocid: String, presetNo: Int, status: String) = CalculationResult(
        ocid = ocid,
        presetNo = presetNo,
        itemName = "test",
        itemLevel = 0,
        itemPart = null,
        itemEquipmentPart = null,
        potentialGrade = null,
        potentialOptions = emptyList(),
        additionalGrade = null,
        additionalOptions = emptyList(),
        currentStar = 0,
        targetStar = 0,
        status = status,
        totalCost = null,
        blackCubeCost = null,
        additionalCubeCost = null,
        starforceCost = null,
    )
}

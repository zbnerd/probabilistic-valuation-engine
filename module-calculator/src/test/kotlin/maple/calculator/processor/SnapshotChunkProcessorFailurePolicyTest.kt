package maple.calculator.processor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maple.calculator.config.PipelineProperties
import maple.expectation.core.calculation.error.MissingProbabilityException
import maple.expectation.core.calculation.error.ProbabilityTableInitializationException
import maple.expectation.core.calculation.error.ValuationInvariantException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.domain.model.CubeType
import maple.expectation.core.dto.v4.AddOption
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentPart
import maple.expectation.core.dto.v4.EquipmentSlot
import maple.expectation.core.dto.v4.StarforceScrollFlag
import maple.expectation.error.exception.InvalidPotentialGradeException
import maple.expectation.error.exception.OptionParseException
import maple.expectation.error.exception.UnsupportedCalculationEngineException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SnapshotChunkProcessorFailurePolicyTest {

    private val policy = ValuationFailurePolicy()

    @Test
    fun `malformed source values remain item-local errors`() {
        assertExistingSchemaError(InvalidPotentialGradeException("INVALID"))
        assertExistingSchemaError(OptionParseException("broken option"))
    }

    @Test
    fun `unsupported source values remain item-local errors`() {
        assertExistingSchemaError(UnsupportedCalculationEngineException("unsupported item"))
    }

    @Test
    fun `missing probability aborts the chunk with the original invariant`() {
        val failure = MissingProbabilityException(
            ProbabilityKey(CubeType.BLACK, 200, "무기", "레전드리", 1),
        )

        assertAbortSame(failure)
    }

    @Test
    fun `probability table initialization failure aborts the chunk`() {
        assertAbortSame(ProbabilityTableInitializationException("table corrupt"))
    }

    @Test
    fun `non finite arithmetic invariant aborts the chunk`() {
        assertAbortSame(ValuationInvariantException("non-finite valuation result"))
    }

    @Test
    fun `kernel invariant after a cache get failure remains the abort cause`() {
        val cacheFailure = IllegalStateException("cache unavailable")
        val kernelFailure = ValuationInvariantException("kernel failed after cache miss", cacheFailure)

        assertAbortSame(kernelFailure)
    }

    @Test
    fun `unexpected runtime failure aborts the chunk with the original cause`() {
        val failure = IllegalStateException("unexpected")

        assertThat(catchThrowable { processorThrowing(failure).calculateItem(flatItem()) })
            .isSameAs(failure)
    }

    private fun assertExistingSchemaError(failure: Throwable) {
        val result = processorThrowing(failure).calculateItem(flatItem())

        assertThat(result.status).isEqualTo("ERROR")
        assertThat(result.errorMessage).isEqualTo(failure.message)
        assertThat(result.totalCost).isNull()
        assertThat(result.blackCubeCost).isNull()
        assertThat(result.additionalCubeCost).isNull()
        assertThat(result.starforceCost).isNull()
        assertThat(result.itemName).isEqualTo("test item")
        assertThat(result.itemPart).isEqualTo("무기")
    }

    private fun processorThrowing(failure: Throwable): SnapshotChunkProcessor {
        val valuationCache = mock<ValuationCache>()
        whenever(valuationCache.getOrCalculate(any())).thenThrow(failure)
        return SnapshotChunkProcessor(
            objectStorage = mock(),
            jsonlReader = mock(),
            equipmentParser = mock(),
            valuationCache = valuationCache,
            failurePolicy = policy,
            objectMapper = jacksonObjectMapper(),
            properties = PipelineProperties(parseWorkers = 1, calcWorkers = 1),
            resultWriter = mock(),
        )
    }

    private fun flatItem(): SnapshotChunkProcessor.FlatItem = SnapshotChunkProcessor.FlatItem(
        ocid = "00000000000000000000000000000000",
        presetNo = 1,
        item = EquipmentItem(
            part = EquipmentSlot.WEAPON,
            equipmentPart = EquipmentPart.WEAPON,
            itemName = "test item",
            level = 200,
            potential = null,
            additionalPotential = null,
            starforce = 0,
            starforceScrollFlag = StarforceScrollFlag.NOT_USED,
            addOption = AddOption(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            baseAttackPower = 0,
            baseMagicPower = 0,
        ),
    )

    private fun assertAbortSame(failure: Throwable) {
        assertThat(catchThrowable { processorThrowing(failure).calculateItem(flatItem()) })
            .isSameAs(failure)
    }
}

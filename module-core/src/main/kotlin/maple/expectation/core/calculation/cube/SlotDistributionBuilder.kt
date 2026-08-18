package maple.expectation.core.calculation.cube

import kotlin.math.abs
import maple.expectation.core.calculation.error.ValuationInvariantException
import maple.expectation.core.calculation.probability.ProbabilityKey
import maple.expectation.core.calculation.probability.ProbabilityRow
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot
import maple.expectation.core.domain.model.calculator.SparsePmf
import maple.expectation.core.domain.stat.StatType

data class SlotDistributionResult(
    val pmf: SparsePmf,
    val massDeviation: Double,
)

class SlotDistributionBuilder(
    private val extractor: StatContributionExtractor = StatContributionExtractor(),
) {
    fun build(
        key: ProbabilityKey,
        targetStat: StatType,
        table: ProbabilityTableSnapshot,
    ): SlotDistributionResult {
        val rows = table.rows(key)
        val totalMass = kahanTotal(rows)
        if (!totalMass.isFinite() || totalMass <= 0.0) {
            throw ValuationInvariantException("Probability mass must be finite and positive for key=$key: $totalMass")
        }

        val distribution = linkedMapOf<Int, Double>()
        rows.forEach { row ->
            val contribution = extractor.extractContributionFor(row.optionName, targetStat)
            val normalizedRate = row.rate / totalMass
            distribution.merge(contribution, normalizedRate, Double::plus)
        }

        val pmf = SparsePmf.fromMap(distribution)
        validate(pmf, key)
        return SlotDistributionResult(
            pmf = pmf,
            massDeviation = abs(totalMass - 1.0).coerceAtMost(1.0),
        )
    }

    private fun kahanTotal(rows: List<ProbabilityRow>): Double {
        var sum = 0.0
        var compensation = 0.0
        rows.forEach { row ->
            val corrected = row.rate - compensation
            val updated = sum + corrected
            compensation = (updated - sum) - corrected
            sum = updated
        }
        return sum
    }

    private fun validate(pmf: SparsePmf, key: ProbabilityKey) {
        if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
            throw ValuationInvariantException("Negative probability in slot distribution for key=$key")
        }
        if (pmf.hasNaNOrInf()) {
            throw ValuationInvariantException("Non-finite probability in slot distribution for key=$key")
        }
        if (pmf.hasValueExceedingOne()) {
            throw ValuationInvariantException("Probability exceeds one in slot distribution for key=$key")
        }

        val total = kahanTotal(pmf.getProbs())
        if (!total.isFinite() || abs(total - 1.0) > NORMALIZED_MASS_TOLERANCE) {
            throw ValuationInvariantException("Normalized slot mass is invalid for key=$key: $total")
        }
    }

    private fun kahanTotal(probabilities: DoubleArray): Double {
        var sum = 0.0
        var compensation = 0.0
        probabilities.forEach { probability ->
            val corrected = probability - compensation
            val updated = sum + corrected
            compensation = (updated - sum) - corrected
            sum = updated
        }
        return sum
    }

    private companion object {
        const val NEGATIVE_TOLERANCE = -1e-15
        const val NORMALIZED_MASS_TOLERANCE = 1e-10
    }
}

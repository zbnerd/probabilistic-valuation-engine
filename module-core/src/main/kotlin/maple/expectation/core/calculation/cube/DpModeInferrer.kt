package maple.expectation.core.calculation.cube

import java.util.EnumMap
import maple.expectation.core.domain.stat.StatType

data class DpInference(
    val targetStatType: StatType?,
    val minTotal: Int,
    val confidence: Double,
    val compound: Boolean,
) {
    val isValid: Boolean
        get() = !compound && targetStatType != null && targetStatType != StatType.UNKNOWN && minTotal > 0
}

class DpModeInferrer(
    private val extractor: StatContributionExtractor = StatContributionExtractor(),
) {
    fun infer(options: List<String>): DpInference {
        if (options.isEmpty()) {
            return EMPTY_INFERENCE
        }

        val contributions = EnumMap<StatType, Int>(StatType::class.java)
        val categories = linkedSetOf<StatType.OptionCategory>()

        options.asSequence()
            .filter { option -> option.isNotBlank() }
            .forEach { option ->
                extractor.extractAll(option).forEach { contribution ->
                    addContribution(contributions, contribution)
                    contribution.type
                        .takeIf { type -> type.isValidCategory() }
                        ?.let { type -> categories.add(type.getCategory()) }
                }
            }

        contributions.remove(StatType.ALLSTAT_PERCENT)
        if (categories.size >= 2) {
            return DpInference(null, 0, 0.0, compound = true)
        }

        val best = contributions.entries
            .asSequence()
            .filter { entry -> entry.value > 0 }
            .fold<Map.Entry<StatType, Int>, Map.Entry<StatType, Int>?>(null) { current, candidate ->
                if (current == null || candidate.value > current.value) candidate else current
            }
            ?: return EMPTY_INFERENCE

        val total = contributions.values.sum()
        if (total <= 0) {
            return EMPTY_INFERENCE
        }

        return DpInference(
            targetStatType = best.key,
            minTotal = best.value,
            confidence = best.value.toDouble() / total,
            compound = false,
        )
    }

    private fun addContribution(
        contributions: EnumMap<StatType, Int>,
        contribution: StatContribution,
    ) {
        contributions.merge(contribution.type, contribution.value, Int::plus)
        if (contribution.type == StatType.ALLSTAT_PERCENT) {
            INDIVIDUAL_PERCENT_STATS.forEach { stat ->
                contributions.merge(stat, contribution.value, Int::plus)
            }
        }
    }

    private companion object {
        val EMPTY_INFERENCE = DpInference(null, 0, 0.0, compound = false)
        val INDIVIDUAL_PERCENT_STATS = listOf(
            StatType.STR_PERCENT,
            StatType.DEX_PERCENT,
            StatType.INT_PERCENT,
            StatType.LUK_PERCENT,
        )
    }
}

package maple.expectation.core.calculation.cube

import maple.expectation.core.domain.stat.StatParser
import maple.expectation.core.domain.stat.StatType
import maple.expectation.error.exception.OptionParseException

data class StatContribution(
    val type: StatType,
    val value: Int,
)

class StatContributionExtractor(
    private val statParser: StatParser = StatParser(),
) {
    fun extractAll(optionName: String?): List<StatContribution> {
        val types = StatType.findAllTypesOrEmpty(optionName)
        if (types.isEmpty() && StatType.looksLikePrimaryStat(optionName)) {
            throw OptionParseException("Primary stat drift detected: $optionName")
        }
        if (types.isEmpty()) {
            return emptyList()
        }

        val value = statParser.parseNum(optionName)
        return types.map { type -> StatContribution(type, value) }
    }

    fun extractContributionFor(optionName: String?, targetType: StatType): Int {
        val contributions = extractAll(optionName)
        contributions.firstOrNull { contribution -> contribution.type == targetType }
            ?.let { contribution -> return contribution.value }

        if (targetType.individualStat) {
            contributions.firstOrNull { contribution -> contribution.type == StatType.ALLSTAT_PERCENT }
                ?.let { contribution -> return contribution.value }
        }

        return 0
    }
}

package maple.expectation.core.domain.model

/**
 * Potential stat domain model.
 *
 * Represents a potential option that can appear on equipment.
 *
 * Pure domain model - no external dependencies.
 *
 * @property optionName the name of the potential option
 * @property statType the type of stat (STR, DEX, INT, LUK, etc.)
 * @property isPercent whether the stat is percentage-based
 */
data class PotentialStat(
    val optionName: String,
    val statType: String,
    val isPercent: Boolean,
) {
    init {
        require(optionName.isNotBlank()) { "optionName cannot be null or blank" }
        require(statType.isNotBlank()) { "statType cannot be null or blank" }
    }

    companion object {
        /**
         * Create a potential stat.
         */
        @JvmStatic
        fun of(optionName: String, statType: String, isPercent: Boolean): PotentialStat = PotentialStat(optionName, statType, isPercent)

        /**
         * Create a percentage-based potential stat.
         */
        @JvmStatic
        fun percentStat(optionName: String, statType: String): PotentialStat = PotentialStat(optionName, statType, true)

        /**
         * Create a flat-value potential stat.
         */
        @JvmStatic
        fun flatStat(optionName: String, statType: String): PotentialStat = PotentialStat(optionName, statType, false)
    }
}

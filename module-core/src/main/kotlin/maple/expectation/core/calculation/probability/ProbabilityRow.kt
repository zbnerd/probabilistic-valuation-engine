package maple.expectation.core.calculation.probability

data class ProbabilityRow(
    val optionName: String,
    val rate: Double,
) {
    init {
        require(optionName.isNotBlank()) { "optionName must not be blank" }
        require(rate.isFinite() && rate in 0.0..1.0) { "rate must be finite and in [0, 1]: $rate" }
    }
}

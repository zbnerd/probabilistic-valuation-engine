package maple.expectation.core.port.out

/**
 * Expectation calculation message payload.
 *
 * <p>Core domain message type for expectation calculation requests.
 * Used by both high and low priority queues.
 *
 * @param userIgn character IGN (identifier)
 * @param forceRecalculation true to bypass cache and force recalculation
 */
data class ExpectationCalcMessage(
    val userIgn: String,
    val forceRecalculation: Boolean,
)

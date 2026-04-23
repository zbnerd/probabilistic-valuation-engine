package maple.expectation.core.port.out

/**
 * Queue name constants for PGMQ queues.
 *
 * <p>Centralized queue name definitions to avoid hardcoding across the codebase.
 */
object QueueNames {
    /** High priority expectation calculation queue (user-initiated requests) */
    const val EXPECTATION_CALC_HIGH = "expectation_calc_high"

    /** Low priority expectation calculation queue (batch/scheduled updates) */
    const val EXPECTATION_CALC_LOW = "expectation_calc_low"
}

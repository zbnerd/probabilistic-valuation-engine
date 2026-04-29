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

    /** Consolidated external API pipeline (OCID resolve + equipment fetch + calculation) */
    const val EXTERNAL_API = "external_api_queue"

    const val NEXON_API_REQUEST = "nexon_api_request_queue"
    const val NEXON_API_RESPONSE = "nexon_api_response_queue"
    const val OCID_RESOLVE = "ocid_resolve_queue"
    const val RESULT_READY = "result_ready_queue"
}

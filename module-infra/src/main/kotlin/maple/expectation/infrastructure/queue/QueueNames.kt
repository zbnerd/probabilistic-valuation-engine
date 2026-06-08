package maple.expectation.infrastructure.queue

/**
 * Queue name constants.
 *
 * <p>String identifiers for message broker queues. These values are
 * adapter-implementation specific (PGMQ in current setup) and are not
 * part of the public domain port contract.
 */
object QueueNames {
    /** High priority expectation calculation queue (user-initiated requests) */
    const val EXPECTATION_CALC_HIGH = "expectation_calc_high"

    /** Low priority expectation calculation queue (batch/scheduled updates) */
    const val EXPECTATION_CALC_LOW = "expectation_calc_low"

    /** External API pipeline (OCID resolve + equipment fetch + input/snapshot staging) */
    const val EXTERNAL_API = "external_api_queue"

    /** CPU-bound calculation pipeline after external API input staging */
    const val CALCULATION_REQUESTED = "calculation_requested_queue"

    /** DB-bound result persistence pipeline after calculation completes */
    const val CALCULATION_COMPLETED = "calculation_completed_queue"

    const val NEXON_API_REQUEST = "nexon_api_request_queue"
    const val NEXON_API_RESPONSE = "nexon_api_response_queue"
    const val OCID_RESOLVE = "ocid_resolve_queue"
    const val RESULT_READY = "result_ready_queue"
}

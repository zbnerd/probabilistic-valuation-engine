package maple.expectation.infrastructure.messaging.kafka

import com.fasterxml.jackson.databind.JsonNode

/**
 * Pipeline-specific behavior for Kafka topics.
 *
 * Extends MQTopicGroup with CAS claim, payload validation,
 * and lease-based processing semantics.
 */
interface PipelineTopic {
    /** Required fields in the JSON payload */
    val requiredFields: List<String>

    /** Required schema version */
    val schemaVersion: Int

    /** DB CAS claim duration in seconds (locked_until) */
    val leaseDurationSeconds: Long

    /**
     * Parse and validate raw String payload.
     * Returns null if poison (routed to DLT by caller).
     */
    fun parseAndValidate(payload: String): JsonNode?

    /**
     * DB CAS claim before side effect.
     * Returns true if claim succeeded, false if already claimed.
     */
    fun claimJob(jobId: String): Boolean
}

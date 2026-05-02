package maple.expectation.core.port.out

/**
 * Kafka topic name constants for the calculation pipeline.
 *
 * Two-topic design: external-api (I/O-bound) and calculation (CPU-bound).
 * DLT topics for poison message isolation.
 */
object KafkaTopicNames {
    const val EXTERNAL_API_REQUESTED = "external-api.requested"
    const val EXTERNAL_API_REQUESTED_DLT = "external-api.requested.DLT"
    const val CALCULATION_REQUESTED = "calculation.requested"
    const val CALCULATION_REQUESTED_DLT = "calculation.requested.DLT"
}

package maple.expectation.core.port.out

/**
 * Port for dispatching calculation pipeline jobs.
 *
 * Implementations route to PGMQ or Kafka based on `app.messaging.transport`.
 * All methods must be safe to call within an existing @Transactional boundary
 * so the dispatch shares the same DB transaction as job state transitions.
 */
interface CalculationDispatchPort {

    fun dispatchExternalApiRequest(jobId: String, userIgn: String, presetNo: Int)

    fun dispatchCalculationRequest(
        jobId: String,
        userIgn: String,
        presetNo: Int,
        characterId: String,
        characterClass: String,
        snapshotId: String,
    )
}

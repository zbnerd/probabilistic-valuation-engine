package maple.expectation.core.port.out

/**
 * PGMQ (PostgreSQL Message Queue) outbound port.
 *
 * <p>Hexagonal architecture boundary for message queue operations.
 * Infrastructure layer implements this port to provide PGMQ functionality.
 */
interface PgmqPort {
    /**
     * Send a message to the specified queue.
     *
     * @param queueName target queue name
     * @param message message payload (will be serialized to JSON)
     * @return message ID
     */
    fun send(queueName: String, message: Any): Long

    /**
     * Get the current length of the specified queue.
     *
     * @param queueName queue name
     * @return number of messages waiting in the queue
     */
    fun queueLength(queueName: String): Long

    /**
     * Find the latest active message ID for a user in the target queue.
     *
     * <p>Used for deduplication - returns the most recent message ID for a user
     * that is still active (not archived).
     *
     * @param queueName queue name
     * @param userIgn user identifier
     * @return message ID if found, null otherwise
     */
    fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long?
}

package maple.expectation.core.port.out

/**
 * Outbound port for message queue operations.
 *
 * <p>Technology-neutral contract. Adapters may target PGMQ, Kafka, RabbitMQ,
 * SQS, or any other message broker. Implementations are selected by
 * configuration and injected as a Spring bean.
 */
interface MessageQueuePort {
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

    /**
     * Atomically send message or return existing message ID if active message found for userIgn.
     * Returns positive ID for new message, negative ID for reused existing message.
     */
    fun sendIfAbsent(queueName: String, userIgn: String, payload: Any): Long
}

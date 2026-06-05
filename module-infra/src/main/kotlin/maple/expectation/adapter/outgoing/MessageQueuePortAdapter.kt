package maple.expectation.adapter.outgoing

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.MessageQueuePort
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PGMQ port adapter implementation.
 *
 * <p>Adapts the infrastructure PgmqClient to the core MessageQueuePort interface.
 * This is the hexagonal architecture adapter that connects the core port
 * to the infrastructure implementation.
 *
 * <h3>Zero Try-Catch</h3>
 * <p>Delegates all operations to PgmqClient, which handles exceptions via LogicExecutor.
 */
@Component
class MessageQueuePortAdapter(
    private val pgmqClient: PgmqClient,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : MessageQueuePort {

    /**
     * Send a message to the specified queue.
     *
     * @param queueName target queue name
     * @param message message payload (will be serialized to JSON)
     * @return message ID
     */
    override fun send(queueName: String, message: Any): Long = pgmqClient.send(queueName, message)

    /**
     * Get the current length of the specified queue.
     *
     * @param queueName queue name
     * @return number of messages waiting in the queue
     */
    override fun queueLength(queueName: String): Long = pgmqClient.queueLength(queueName)

    /**
     * Find the latest active message ID for a user in the target queue.
     *
     * @param queueName queue name
     * @param userIgn user identifier
     * @return message ID if found, null otherwise
     */
    override fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long? = pgmqClient.findActiveMessageIdByUserIgn(queueName, userIgn)

    /**
     * Atomically send message or return existing message ID if active message found for userIgn.
     * Returns positive ID for new message, negative ID for reused existing message.
     */
    override fun sendIfAbsent(queueName: String, userIgn: String, payload: Any): Long {
        val json = objectMapper.writeValueAsString(payload)
        return jdbcTemplate.queryForObject(
            "SELECT pgmq_send_if_absent(?, ?, ?::jsonb)",
            Long::class.java,
            queueName,
            userIgn,
            json,
        ) ?: throw IllegalStateException("Failed to execute sendIfAbsent for queue: $queueName")
    }
}

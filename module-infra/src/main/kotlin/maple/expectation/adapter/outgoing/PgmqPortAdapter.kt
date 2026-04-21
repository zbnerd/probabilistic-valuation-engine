package maple.expectation.adapter.outgoing

import maple.expectation.core.port.out.PgmqPort
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.springframework.stereotype.Component

/**
 * PGMQ port adapter implementation.
 *
 * <p>Adapts the infrastructure PgmqClient to the core PgmqPort interface.
 * This is the hexagonal architecture adapter that connects the core port
 * to the infrastructure implementation.
 *
 * <h3>Zero Try-Catch</h3>
 * <p>Delegates all operations to PgmqClient, which handles exceptions via LogicExecutor.
 */
@Component
class PgmqPortAdapter(
    private val pgmqClient: PgmqClient,
) : PgmqPort {

    /**
     * Send a message to the specified queue.
     *
     * @param queueName target queue name
     * @param message message payload (will be serialized to JSON)
     * @return message ID
     */
    override fun send(queueName: String, message: Any): Long {
        return pgmqClient.send(queueName, message)
    }

    /**
     * Get the current length of the specified queue.
     *
     * @param queueName queue name
     * @return number of messages waiting in the queue
     */
    override fun queueLength(queueName: String): Long {
        return pgmqClient.queueLength(queueName)
    }

    /**
     * Find the latest active message ID for a user in the target queue.
     *
     * @param queueName queue name
     * @param userIgn user identifier
     * @return message ID if found, null otherwise
     */
    override fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long? {
        return pgmqClient.findActiveMessageIdByUserIgn(queueName, userIgn)
    }
}

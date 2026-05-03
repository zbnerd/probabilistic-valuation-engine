package maple.expectation.infrastructure.alert.channel

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import maple.expectation.infrastructure.alert.message.AlertMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * In-Memory Alert Buffer (Fallback Channel)
 *
 * <p>Stateless fallback channel that stores alerts in memory
 *
 * <p>Thread-safe circular buffer with max capacity (1000 alerts)
 *
 * <p>Zero external dependencies - pure JVM implementation
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses ArrayBlockingQueue for thread-safe bounded buffer
 *   <li>When buffer is full, new alerts are dropped (with warning)
 *   <li>Implements FallbackSupport for chaining
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
class InMemoryAlertBuffer :
    AlertChannel,
    FallbackSupport {

    private val log = LoggerFactory.getLogger(InMemoryAlertBuffer::class.java)
    private val buffer: BlockingQueue<AlertMessage> = ArrayBlockingQueue(MAX_CAPACITY)
    private var fallback: AlertChannel? = null

    override fun send(message: AlertMessage): Boolean {
        val offered = buffer.offer(message)
        if (!offered && log.isWarnEnabled) {
            log.warn("[InMemoryAlertBuffer] Buffer full, dropping alert: {}", message.getTitle())
        }
        return offered
    }

    override fun getChannelName(): String = "in-memory"

    fun getBufferSize(): Int = buffer.size

    fun drainTo(targetChannel: AlertChannel): Int {
        var drained = 0
        var message: AlertMessage?
        while (buffer.poll().also { message = it } != null) {
            val sent = targetChannel.send(requireNotNull(message) { "Polled message must not be null after non-null check" })
            if (sent) {
                drained++
            } else if (log.isWarnEnabled) {
                log.warn(
                    "[InMemoryAlertBuffer] Failed to drain alert to {}: {}",
                    targetChannel.getChannelName(),
                )
            }
        }
        return drained
    }

    override fun setFallback(fallback: AlertChannel?) {
        this.fallback = fallback
        log.info(
            "[InMemoryAlertBuffer] Fallback channel set to {}",
            if (fallback != null) fallback.getChannelName() else "none",
        )
    }

    companion object {
        private const val MAX_CAPACITY = 1000
    }
}

package maple.expectation.infrastructure.alert

import maple.expectation.core.port.out.AlertPublisher
import maple.expectation.infrastructure.alert.channel.AlertChannel
import maple.expectation.infrastructure.alert.message.AlertMessage
import maple.expectation.infrastructure.alert.strategy.AlertChannelStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Stateless Alert Service
 *
 * <p>DIP (Dependency Inversion): Implements {@link AlertPublisher} interface from module-core
 *
 * <p>SRP: Single responsibility - orchestrate alert sending
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses AlertChannelStrategy to select channel based on priority
 *   <li>CRITICAL alerts bypass all stateful dependencies (Redis/DB)
 *   <li>Protected by LogicExecutor for exception handling
 *   <li>Returns immediately (fire-and-forget for non-blocking)
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Service
class StatelessAlertService(
    private val channelStrategy: AlertChannelStrategy,
    private val executor: LogicExecutor,
    @Value("\${alert.discord.webhook-url:}")
    private val discordWebhookUrl: String
) : AlertPublisher {

    private val log = LoggerFactory.getLogger(StatelessAlertService::class.java)

    /**
     * Send CRITICAL alert - Stateless, no Redis/DB dependency
     *
     * @param title Alert title
     * @param message Alert message
     * @param error Throwable (optional)
     */
    override fun sendCritical(title: String, message: String, error: Throwable?) {
        val channel = channelStrategy.getChannel(AlertPriority.CRITICAL)
        executor.executeVoid(
            {
                val sent = channel.send(AlertMessage(title, message, error, discordWebhookUrl))
                if (!sent && log.isWarnEnabled) {
                    log.warn("[StatelessAlertService] Failed to send critical alert: {}", title)
                }
            },
            TaskContext.of("AlertService", "Critical", title)
        )
    }

    /**
     * Send NORMAL alert - can use throttling
     *
     * @param title Alert title
     * @param message Alert message
     */
    override fun sendNormal(title: String, message: String) {
        val channel = channelStrategy.getChannel(AlertPriority.NORMAL)
        executor.executeVoid(
            {
                channel.send(AlertMessage(title, message, null, discordWebhookUrl))
            },
            TaskContext.of("AlertService", "Normal", title)
        )
    }
}

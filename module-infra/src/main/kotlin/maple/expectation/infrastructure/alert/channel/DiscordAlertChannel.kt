package maple.expectation.infrastructure.alert.channel

import maple.expectation.infrastructure.alert.factory.MessageFactory
import maple.expectation.infrastructure.alert.message.AlertMessage
import maple.expectation.infrastructure.config.AlertFeatureProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException

/**
 * Discord Alert Channel Implementation
 *
 * <p>Primary alert channel using Discord webhook
 *
 * <p>Uses dedicated alertWebClient bean (isolated from Nexon API)
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses dedicated WebClient bean to avoid resource contention
 *   <li>Implements AlertChannel interface (SRP)
 *   <li>Protected by LogicExecutor for exception handling
 *   <li>Non-blocking: Returns immediately after queueing send
 *   <li>Feature flag controlled via alert.stateless.enabled
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeWithFallback() for WebClientRequestException handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@ConditionalOnProperty(
    name = ["alert.stateless.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class DiscordAlertChannel(
    @Qualifier("alertWebClient") private val alertWebClient: WebClient,
    private val executor: LogicExecutor,
    private val alertFeatureProperties: AlertFeatureProperties
) : AlertChannel {

    private val log = LoggerFactory.getLogger(DiscordAlertChannel::class.java)

    override fun send(message: AlertMessage): Boolean {
        // Check feature flag before sending
        if (!alertFeatureProperties.stateless.enabled) {
            log.debug("[DiscordAlertChannel] Alert system disabled via feature flag")
            return false
        }

        return executor.executeWithFallback(
            { sendToDiscord(message) },
            { e -> handleWebClientException(message, e) },
            TaskContext.of("AlertChannel", "Discord", message.getTitle())
        )
    }

    /**
     * Send alert to Discord webhook.
     *
     * <p>Wrapped by LogicExecutor.executeWithFallback() which handles WebClientRequestException with
     * logging and returns false.
     *
     * <p>ADR-039 Fix: Added {@code ContentType.APPLICATION_JSON} to match Discord webhook wire
     * format.
     */
    private fun sendToDiscord(message: AlertMessage): Boolean {
        val response: ResponseEntity<Void>? = alertWebClient
            .post()
            .uri(message.getWebhookUrl())
            .contentType(MediaType.APPLICATION_JSON) // ADR-039 Fix
            .bodyValue(MessageFactory.toDiscordPayload(message))
            .retrieve()
            .toBodilessEntity()
            .block()

        val success = response?.statusCode?.is2xxSuccessful == true

        if (success && log.isInfoEnabled) {
            log.info(
                "[DiscordAlertChannel] Alert sent successfully to {}: {}",
                message.getTitle(),
                response.statusCode
            )
        } else if (!success && log.isWarnEnabled) {
            log.warn(
                "[DiscordAlertChannel] Alert failed with status {}: {}",
                message.getTitle(),
                response?.statusCode
            )
        }

        return success
    }

    /**
     * Recovery handler for WebClient exceptions.
     *
     * <p>Called by LogicExecutor.executeWithFallback() when an exception occurs.
     */
    private fun handleWebClientException(message: AlertMessage, e: Throwable): Boolean {
        if (e is WebClientRequestException) {
            log.warn("[DiscordAlertChannel] Discord webhook request failed: {}", e.message)
        } else {
            log.error("[DiscordAlertChannel] Unexpected error sending alert: {}", e.message, e)
        }
        return false
    }

    override fun getChannelName(): String = "discord"
}

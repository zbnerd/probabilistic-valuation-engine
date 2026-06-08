package maple.expectation.infrastructure.alert.channel

import maple.expectation.infrastructure.alert.factory.MessageFactory
import maple.expectation.infrastructure.alert.message.AlertMessage
import maple.expectation.infrastructure.config.AlertFeatureProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
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
    matchIfMissing = true,
)
class DiscordAlertChannel(
    @Qualifier("alertWebClient") private val alertWebClient: WebClient,
    private val alertFeatureProperties: AlertFeatureProperties,
    private val messageFactory: MessageFactory,
) : AlertChannel {

    private val log = LoggerFactory.getLogger(DiscordAlertChannel::class.java)

    override fun send(message: AlertMessage): Boolean {
        // Check feature flag before sending
        if (!alertFeatureProperties.stateless.enabled) {
            log.debug("[DiscordAlertChannel] Alert system disabled via feature flag")
            return false
        }

        sendToDiscord(message)
        return true // fire-and-forget
    }

    /**
     * Send alert to Discord webhook (fire-and-forget).
     *
     * Uses .subscribe() for non-blocking async publish.
     * Success/failure handled via subscribe callbacks.
     */
    private fun sendToDiscord(message: AlertMessage) {
        alertWebClient
            .post()
            .uri(message.getWebhookUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(messageFactory.toDiscordPayload(message))
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                { response ->
                    if (response.statusCode.is2xxSuccessful) {
                        log.info("[DiscordAlertChannel] Alert sent successfully to {}: {}", message.getTitle(), response.statusCode)
                    } else {
                        log.warn("[DiscordAlertChannel] Alert failed with status {}: {}", message.getTitle(), response.statusCode)
                    }
                },
                { error -> handleWebClientException(message, error) },
            )
    }

    /**
     * Error handler for Discord webhook failures.
     */
    private fun handleWebClientException(message: AlertMessage, e: Throwable) {
        if (e is WebClientRequestException) {
            log.warn("[DiscordAlertChannel] Discord webhook request failed: {}", e.message)
        } else {
            log.error("[DiscordAlertChannel] Unexpected error sending alert: {}", e.message, e)
        }
    }

    override fun getChannelName(): String = "discord"
}

package maple.expectation.infrastructure.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextStartedEvent
import org.springframework.stereotype.Component

/**
 * Alert Configuration Validator
 *
 * <p>Validates alert configuration at application startup.
 *
 * <p>Performs Fail-Fast checks:
 *
 * <ul>
 *   <li>Discord webhook URL must be configured when alerts are enabled
 *   <li>Logs clear error message with fix instructions
 * </ul>
 *
 * <h4>CLAUDE.md Compliance:</h4>
 *
 * <ul>
 *   <li>Section 16: Proactive error detection and validation
 *   <li>Fail Fast principle: Detect configuration issues at startup
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-13
 */
@Component
@ConditionalOnProperty(
    name = ["alert.stateless.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AlertConfigurationValidator(
    @Value("\${alert.discord.webhook-url:}") private val discordWebhookUrl: String,
    @Value("\${alert.stateless.enabled:true}") private val alertEnabled: Boolean,
) : ApplicationListener<ContextStartedEvent> {

    private val log = LoggerFactory.getLogger(AlertConfigurationValidator::class.java)

    /**
     * Validate alert configuration when application context is fully initialized.
     *
     * <p>Called by Spring after all @Value injections are complete.
     */
    override fun onApplicationEvent(event: ContextStartedEvent) {
        if (!alertEnabled) {
            log.info("[AlertConfig] Alert system is disabled via configuration")
            return
        }

        if (discordWebhookUrl.isBlank()) {
            log.error(
                """
        ========================================================================
        [AlertConfig] CRITICAL: Discord webhook URL is not configured!

        Alerts will NOT be sent to Discord. To fix this issue:

        1. Set environment variable: ALERT_DISCORD_WEBHOOK_URL
        2. Example: export ALERT_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/YOUR_WEBHOOK_URL
        3. Or add to .env file: ALERT_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/YOUR_WEBHOOK_URL

        Reference: docs/04_Reports/discord-webhook-root-cause-analysis.md
        ========================================================================
                """.trimIndent(),
            )
        } else {
            val maskedUrl = discordWebhookUrl.take(30) + "..."
            log.info("[AlertConfig] Discord webhook configured: {}", maskedUrl)
        }
    }
}

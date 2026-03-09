package maple.expectation.infrastructure.alert.strategy

import java.util.function.Supplier
import maple.expectation.infrastructure.alert.AlertPriority
import maple.expectation.infrastructure.alert.channel.AlertChannel
import org.springframework.stereotype.Component

/**
 * Stateless Alert Channel Strategy
 *
 * <p>OCP (Open/Closed Principle): New alert channels without modifying existing code
 *
 * <p>Selects appropriate channel based on alert priority
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
class StatelessAlertChannelStrategy(
    private val channelProviders: Map<AlertPriority, Supplier<AlertChannel>>,
) : AlertChannelStrategy {

    /**
     * Get alert channel based on priority
     *
     * @param priority Alert priority (CRITICAL, NORMAL, BACKGROUND)
     * @return Alert channel implementation
     */
    override fun getChannel(priority: AlertPriority): AlertChannel {
        // ADR-039 Fix: Use Discord as fallback default channel
        // Since DiscordAlertChannel is always available (via @ConditionalOnProperty),
        // we use it as the default instead of throwing UnsupportedOperationException
        return channelProviders[priority]?.get() ?: getDefaultChannel()
    }

    /**
     * ADR-039 Fix: Returns Discord channel as default.
     *
     * <p>Previously threw {@code UnsupportedOperationException}. Now falls back to Discord channel
     * which is always configured when {@code alert.stateless.enabled=true}.
     *
     * @return Discord alert channel
     */
    private fun getDefaultChannel(): AlertChannel {
        // Use CRITICAL priority as default (Discord is always configured for high-priority alerts)
        val discordChannel = channelProviders[AlertPriority.CRITICAL]?.get()
        if (discordChannel != null) {
            return discordChannel
        }
        // If Discord channel is not available (shouldn't happen), throw with clear message
        throw IllegalStateException(
            "No alert channel configured. Please configure alert.stateless.enabled=true",
        )
    }

    companion object {
        /**
         * Bean name for test compatibility.
         *
         * <p>Tests may reference this bean by name. Using @Component ensures the bean is always
         * available, even when alert.stateless.enabled=false.
         */
        const val BEAN_NAME = "statelessAlertChannelStrategy"
    }
}

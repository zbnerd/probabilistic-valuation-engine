package maple.expectation.infrastructure.config

import java.nio.file.Path
import java.util.function.Supplier
import maple.expectation.infrastructure.alert.AlertPriority
import maple.expectation.infrastructure.alert.channel.AlertChannel
import maple.expectation.infrastructure.alert.channel.InMemoryAlertBuffer
import maple.expectation.infrastructure.alert.channel.LocalFileAlertChannel
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Alert Channel Configuration
 *
 * <p>Defines channel providers mapping for each alert priority level
 *
 * <p>Channel Selection Strategy:
 *
 * <ul>
 *   <li>CRITICAL: DiscordAlertChannel (immediate external notification)
 *   <li>NORMAL: InMemoryAlertBuffer (buffered for batch processing)
 *   <li>BACKGROUND: InMemoryAlertBuffer (buffered for low-priority batch)
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-13
 */
@Configuration
class AlertChannelConfig(
    private val discordAlertChannel: maple.expectation.infrastructure.alert.channel.DiscordAlertChannel,
    private val inMemoryAlertBuffer: InMemoryAlertBuffer,
    private val alertFeatureProperties: AlertFeatureProperties,
    private val logicExecutor: LogicExecutor,
) {

    /**
     * Alert Log File Path Bean
     *
     * <p>Creates Path bean from configured alert file path
     *
     * @return Path to alert log file
     */
    @Bean
    fun alertLogFilePath(): Path = Path.of(alertFeatureProperties.file.path)

    /**
     * Local File Alert Channel Bean
     *
     * <p>Creates file-based alert channel as fallback
     *
     * @return LocalFileAlertChannel instance
     */
    @Bean
    fun localFileAlertChannel(alertLogFilePath: Path): LocalFileAlertChannel = LocalFileAlertChannel(alertLogFilePath, logicExecutor)

    /**
     * Channel Providers Bean
     *
     * <p>Maps AlertPriority to Supplier<AlertChannel> for lazy channel resolution
     *
     * @return Map of priority to channel provider
     */
    @Bean
    fun channelProviders(): Map<AlertPriority, Supplier<AlertChannel>> = mapOf(
        AlertPriority.CRITICAL to Supplier { discordAlertChannel },
        AlertPriority.NORMAL to Supplier { inMemoryAlertBuffer },
        AlertPriority.BACKGROUND to Supplier { inMemoryAlertBuffer },
    )
}

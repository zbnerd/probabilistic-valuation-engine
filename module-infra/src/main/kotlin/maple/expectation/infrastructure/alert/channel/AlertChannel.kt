package maple.expectation.infrastructure.alert.channel

import maple.expectation.infrastructure.alert.message.AlertMessage

/**
 * Alert Channel Interface
 *
 * <p>SRP (Single Responsibility): Alert 전송의 책임만 정의
 *
 * <p>All channels must be thread-safe and non-blocking
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
interface AlertChannel {

    /**
     * Send alert message through this channel
     *
     * @param message Alert message to send
     * @return true if sent successfully, false otherwise
     */
    fun send(message: AlertMessage): Boolean

    /**
     * Get channel name for monitoring
     *
     * @return Channel identifier (e.g., "discord", "in-memory")
     */
    fun getChannelName(): String
}

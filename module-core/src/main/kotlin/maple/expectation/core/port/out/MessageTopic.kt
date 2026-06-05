package maple.expectation.core.port.out

import java.util.function.BiConsumer

/**
 * Message topic for pub/sub pattern.
 *
 * <p>Domain port for publish-subscribe messaging. Adapters wrap any
 * pub/sub technology (message broker topics, distributed cache pub/sub,
 * event bus topics, etc.). Business logic depends on this interface.
 *
 * @param <T> message type
 */
interface MessageTopic<T> {

    /**
     * Add a listener for messages on this topic.
     *
     * @param messageType message class type
     * @param listener listener receiving (channel, message) pair
     * @return listener ID for removal
     */
    fun addListener(messageType: Class<T>, listener: BiConsumer<String, T>): Int

    /**
     * Remove a listener by ID.
     *
     * @param listenerId listener ID from {@link #addListener}
     */
    fun removeListener(listenerId: Int)

    /**
     * Publish a message to the topic.
     *
     * @param channel channel name (e.g., instance ID)
     * @param message message to publish
     */
    fun publish(channel: String, message: T)
}

package maple.expectation.core.port.out

import java.util.function.BiConsumer

/**
 * Message topic for pub/sub pattern.
 *
 * <p>Domain port for publish-subscribe messaging. Abstraction over Redis topics, Kafka topics, etc.
 * Supports DIP (Dependency Inversion Principle) by allowing business logic to depend on this
 * interface instead of concrete Redis APIs.
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

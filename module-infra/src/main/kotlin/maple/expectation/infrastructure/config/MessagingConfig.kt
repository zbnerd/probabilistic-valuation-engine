package maple.expectation.infrastructure.config

import maple.expectation.common.resource.ResourceLoader
import maple.expectation.core.port.out.MessageQueue
import maple.expectation.core.port.out.MessageTopic
import maple.expectation.infrastructure.messaging.RedisMessageQueue
import maple.expectation.infrastructure.messaging.RedisMessageTopic
import org.redisson.api.RedissonClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Messaging infrastructure configuration.
 *
 * <p>Configures MessageTopic and MessageQueue beans using Redisson.
 */
@Configuration
class MessagingConfig {

    @Bean
    fun characterEventTopic(redissonClient: RedissonClient): MessageTopic<String> = RedisMessageTopic(redissonClient, "char_event")

    @Bean
    fun characterJobQueue(redissonClient: RedissonClient): MessageQueue<String> = RedisMessageQueue(redissonClient, "character_job_queue")

    @Bean
    fun nexonDataQueue(redissonClient: RedissonClient): MessageQueue<String> = RedisMessageQueue(redissonClient, "nexon-data")

    /**
     * Event queue for RedisEventPublisher to publish IntegrationEvent messages.
     *
     * <p>Separate from characterJobQueue and nexonDataQueue to avoid ambiguity. This queue is
     * specifically for domain events published through EventPublisher interface.
     */
    @Bean("integrationEventQueue")
    fun integrationEventQueue(redissonClient: RedissonClient): MessageQueue<String> = RedisMessageQueue(redissonClient, "integration_event_queue")

    /**
     * ResourceLoader bean for loading classpath resources. Required by TwoBucketRateLimiter for
     * loading Lua scripts.
     */
    @Bean
    fun resourceLoader(): ResourceLoader = ResourceLoader()
}

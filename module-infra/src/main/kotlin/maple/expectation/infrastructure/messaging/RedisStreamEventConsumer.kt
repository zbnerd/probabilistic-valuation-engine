package maple.expectation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import maple.expectation.domain.event.IntegrationEvent
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.EventProcessingException
import maple.expectation.event.EventHandler
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.api.StreamMessageId
import org.redisson.api.stream.StreamCreateGroupArgs
import org.redisson.api.stream.StreamReadGroupArgs
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import java.lang.reflect.Method
import java.time.Duration
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

class RedisStreamEventConsumer(
    redissonClient: RedissonClient,
    private val objectMapper: ObjectMapper,
    private val deduplicationFilter: DeduplicationFilter,
    private val executor: LogicExecutor,
    private val observationRegistry: ObservationRegistry,
    private val streamKey: String,
    private val consumerGroup: String,
    private val consumerName: String,
    private val readTimeout: Duration
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(RedisStreamEventConsumer::class.java)
    private val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
    private val handlerCache = ConcurrentHashMap<String, MutableList<HandlerMethod>>()

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        discoverHandlers(applicationContext)
    }

    private fun discoverHandlers(context: ApplicationContext) {
        executor.executeVoidJava(
            {
                try {
                    discoverHandlersInternal(context)
                } catch (e: Exception) {
                    logger.error("[RedisStreamEventConsumer] Handler discovery failed for stream: {}", streamKey, e)
                }
            },
            TaskContext.of("RedisStreamEventConsumer", "DiscoverHandlers", streamKey)
        )
    }

    private fun discoverHandlersInternal(context: ApplicationContext) {
        for (bean in context.getBeansOfType(Any::class.java).values) {
            val beanClass = bean.javaClass
            val beanName = context.getBeansOfType(Any::class.java).entries
                .firstOrNull { it.value === bean }?.key ?: beanClass.simpleName

            for (method in beanClass.declaredMethods) {
                if (!method.isAnnotationPresent(EventHandler::class.java)) {
                    continue
                }

                val annotation = method.getAnnotation(EventHandler::class.java)
                val eventType = annotation.eventType.java.simpleName

                validateHandlerMethod(method, annotation.eventType.java)

                val handlers = handlerCache.computeIfAbsent(eventType) { ArrayList() }
                handlers.add(HandlerMethod(bean, method, annotation.async))

                logger.info(
                    "[RedisStreamEventConsumer] Discovered handler: eventType={}, method={}, bean={}",
                    eventType,
                    method.name,
                    beanName
                )
            }
        }

        logger.info(
            "[RedisStreamEventConsumer] Handler discovery complete: {} event types, {} handlers",
            handlerCache.size,
            handlerCache.values.sumOf { it.size }
        )
    }

    private fun validateHandlerMethod(method: Method, expectedType: Class<*>) {
        if (method.parameterCount != 1) {
            throw EventProcessingException(
                CommonErrorCode.EVENT_HANDLER_ERROR,
                "Handler method must have single parameter: ${method.declaringClass.simpleName}.${method.name} (params=${method.parameterCount})"
            )
        }

        val paramType = method.parameterTypes[0]
        if (!expectedType.isAssignableFrom(paramType)) {
            throw EventProcessingException(
                CommonErrorCode.EVENT_HANDLER_ERROR,
                "Handler parameter type mismatch: ${method.declaringClass.simpleName}.${method.name} (expected=${expectedType.simpleName}, actual=${paramType.simpleName})"
            )
        }
    }

    private fun findHandlerForEvent(eventType: String): List<HandlerMethod> {
        return handlerCache[eventType] ?: emptyList()
    }

    fun startConsuming() {
        logger.info(
            "[RedisStreamEventConsumer] Starting consumer: group={}, name={}, stream={}",
            consumerGroup,
            consumerName,
            streamKey
        )

        while (!Thread.currentThread().isInterrupted) {
            executor.executeVoidJava(
                {
                    try {
                        consumeNextBatch()
                    } catch (e: Exception) {
                        logger.error("[RedisStreamEventConsumer] Batch consumption failed for stream: {}", streamKey, e)
                    }
                },
                TaskContext.of("RedisStreamEventConsumer", "ConsumeBatch", streamKey)
            )
        }

        logger.info(
            "[RedisStreamEventConsumer] Consumer stopped: group={}, name={}",
            consumerGroup,
            consumerName
        )
    }

    private fun consumeNextBatch() {
        val messages = stream.readGroup(
            consumerGroup,
            consumerName,
            StreamReadGroupArgs.neverDelivered().timeout(readTimeout)
        )

        if (messages.isNullOrEmpty()) {
            return
        }

        for ((messageId, fields) in messages) {
            processMessage(messageId, fields)
        }
    }

    private fun processMessage(messageId: StreamMessageId, fields: Map<String, String>) {
        executor.executeVoidJava(
            {
                try {
                    processMessageInternal(messageId, fields)
                } catch (e: Exception) {
                    logger.error("[RedisStreamEventConsumer] Message processing failed for messageId: {}", messageId, e)
                }
            },
            TaskContext.of("RedisStreamEventConsumer", "ProcessMessage", messageId.toString())
        )
    }

    private fun processMessageInternal(messageId: StreamMessageId, fields: Map<String, String>) {
        val jsonPayload = fields["payload"]
        if (jsonPayload == null) {
            logger.warn("[RedisStreamEventConsumer] Missing payload field: messageId={}", messageId)
            stream.ack(consumerGroup, messageId)
            return
        }

        val typeFactory = objectMapper.typeFactory
        val event: IntegrationEvent<*> = objectMapper.readValue(
            jsonPayload,
            typeFactory.constructParametricType(IntegrationEvent::class.java, Any::class.java)
        )

        if (deduplicationFilter.isDuplicate(event.eventId)) {
            logger.debug(
                "[RedisStreamEventConsumer] Duplicate event skipped: eventId={}, messageId={}",
                event.eventId,
                messageId
            )
            stream.ack(consumerGroup, messageId)
            return
        }

        val eventType = event.eventType
        val handlers = findHandlerForEvent(eventType)

        if (handlers.isEmpty()) {
            logger.warn("[RedisStreamEventConsumer] No handlers found for eventType={}", eventType)
            stream.ack(consumerGroup, messageId)
            return
        }

        Observation.createNotStarted("redis.stream.consumer", observationRegistry)
            .lowCardinalityKeyValue("event.type", eventType)
            .observe {
                dispatchToHandlers(event, handlers)
                logger.debug(
                    "[RedisStreamEventConsumer] Dispatched: eventId={}, eventType={}, handlers={}",
                    event.eventId,
                    eventType,
                    handlers.size
                )
            }

        val ackCount = stream.ack(consumerGroup, messageId)
        if (ackCount == 0L) {
            logger.warn(
                "[RedisStreamEventConsumer] XACK failed (message already acknowledged?): messageId={}",
                messageId
            )
        }
    }

    private fun dispatchToHandlers(event: IntegrationEvent<*>, handlers: List<HandlerMethod>) {
        for (handler in handlers) {
            try {
                invokeHandler(handler, event)
            } catch (e: Exception) {
                logger.error(
                    "[RedisStreamEventConsumer] Handler failed: method={}, eventType={}",
                    handler.method.name,
                    event.eventType,
                    e
                )
                throw EventProcessingException(
                    CommonErrorCode.EVENT_HANDLER_ERROR,
                    e,
                    "Handler failed for event: ${event.eventType}"
                )
            }
        }
    }

    @Throws(Exception::class)
    private fun invokeHandler(handler: HandlerMethod, event: IntegrationEvent<*>) {
        val payload = event.payload
        handler.method.invoke(handler.bean, payload)
    }

    private fun createConsumerGroupIfNeeded() {
        executor.executeVoidJava(
            {
                try {
                    stream.createGroup(StreamCreateGroupArgs.name(consumerGroup).makeStream())
                    logger.info(
                        "[RedisStreamEventConsumer] Created consumer group: stream={}, group={}",
                        streamKey,
                        consumerGroup
                    )
                } catch (e: Exception) {
                    if (e.message?.contains("BUSYGROUP") == true) {
                        logger.debug(
                            "[RedisStreamEventConsumer] Consumer group already exists: stream={}, group={}",
                            streamKey,
                            consumerGroup
                        )
                    } else {
                        throw EventProcessingException(
                            CommonErrorCode.EVENT_CONSUMER_ERROR,
                            e,
                            "Failed to create consumer group: $consumerGroup"
                        )
                    }
                }
            },
            TaskContext.of("RedisStreamEventConsumer", "CreateGroup", consumerGroup)
        )
    }

    fun getPendingCount(): Long {
        return executor.executeOrDefault(
            { 0L },
            0L,
            TaskContext.of("RedisStreamEventConsumer", "PendingCount")
        )
    }

    fun getHandlerTypeCount(): Int = handlerCache.size

    private data class HandlerMethod(
        val bean: Any,
        val method: Method,
        val async: Boolean
    ) {
        init {
            method.isAccessible = true
        }
    }

    init {
        createConsumerGroupIfNeeded()
    }
}

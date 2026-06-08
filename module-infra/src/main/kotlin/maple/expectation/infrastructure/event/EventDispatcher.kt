package maple.expectation.infrastructure.event

import jakarta.annotation.PreDestroy
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.EventProcessingException
import maple.expectation.event.EventHandler
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.VirtualThreadExecutorManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class EventDispatcher(
    private val executor: LogicExecutor,
    @Value("\${app.event.dispatcher.async:true}") private val enableAsync: Boolean,
) {
    private val logger = LoggerFactory.getLogger(EventDispatcher::class.java)
    private val exec = if (enableAsync) VirtualThreadExecutorManager("EventDispatcher") else null
    private val virtualThreadExecutor: Executor = exec?.executor ?: Executor { it.run() }
    private val handlers: MutableMap<Class<*>, MutableList<HandlerMethod>> = ConcurrentHashMap()

    init {
        logger.info(
            "[EventDispatcher] Initialized: async={}, executorType={}",
            enableAsync,
            if (enableAsync) "VirtualThreads" else "Synchronous",
        )
    }

    fun registerHandlers(component: Any) {
        executor.executeVoidJava(
            { registerHandlersInternal(component) },
            TaskContext.of("EventDispatcher", "RegisterHandlers", component.javaClass.simpleName),
        )
    }

    private fun registerHandlersInternal(component: Any) {
        val componentClass = component.javaClass
        var registered = 0

        for (method in componentClass.declaredMethods) {
            if (!method.isAnnotationPresent(EventHandler::class.java)) {
                continue
            }

            val annotation = method.getAnnotation(EventHandler::class.java)
            val eventType = annotation.eventType.java
            val async = annotation.async

            validateHandlerMethod(method, eventType)

            val handler = HandlerMethod(component, method, async)
            handlers.computeIfAbsent(eventType) { ArrayList() }.add(handler)

            registered++
            logger.debug(
                "[EventDispatcher] Registered handler: type={}, method={}, async={}",
                eventType.simpleName,
                method.name,
                async,
            )
        }

        if (registered > 0) {
            logger.info(
                "[EventDispatcher] Registered {} handlers from component: {}",
                registered,
                componentClass.simpleName,
            )
        }
    }

    private fun validateHandlerMethod(method: Method, expectedType: Class<*>) {
        if (method.parameterCount != 1) {
            throw EventProcessingException(
                CommonErrorCode.EVENT_HANDLER_ERROR,
                "Handler method must have single parameter: ${method.declaringClass.simpleName}.${method.name} (params=${method.parameterCount})",
            )
        }

        val paramType = method.parameterTypes[0]
        if (!expectedType.isAssignableFrom(paramType)) {
            throw EventProcessingException(
                CommonErrorCode.EVENT_HANDLER_ERROR,
                "Handler parameter type mismatch: ${method.declaringClass.simpleName}.${method.name} (expected=${expectedType.simpleName}, actual=${paramType.simpleName})",
            )
        }
    }

    fun dispatch(event: IntegrationEvent<*>) {
        executor.executeVoidJava(
            { dispatchInternal(event) },
            TaskContext.of("EventDispatcher", "Dispatch", event.eventType),
        )
    }

    private fun dispatchInternal(event: IntegrationEvent<*>) {
        val payload = requireNotNull(event.payload) { "Event payload must not be null for eventType=${event.eventType}" }
        val eventType = payload.javaClass
        val eventHandlers = handlers[eventType]

        if (eventHandlers.isNullOrEmpty()) {
            logger.debug("[EventDispatcher] No handlers registered for event type: {}", eventType.simpleName)
            return
        }

        for (handler in eventHandlers) {
            executeHandler(handler, event)
        }
    }

    private fun executeHandler(handler: HandlerMethod, event: IntegrationEvent<*>) {
        if (handler.async) {
            virtualThreadExecutor.execute {
                executor.executeOrCatch(
                    { invokeHandler(handler, event) },
                    { e -> logHandlerFailure(handler, event, e) },
                    TaskContext.of("EventDispatcher", "InvokeAsync", handler.method.name),
                )
            }
        } else {
            executor.executeOrCatch(
                { invokeHandler(handler, event) },
                { e -> logHandlerFailure(handler, event, e) },
                TaskContext.of("EventDispatcher", "InvokeSync", handler.method.name),
            )
        }
    }

    private fun logHandlerFailure(handler: HandlerMethod, event: IntegrationEvent<*>, error: Throwable) {
        logger.error(
            "[EventDispatcher] Handler failed: eventId={}, eventType={}, handler={}",
            event.eventId,
            event.eventType,
            handler.method.name,
            error,
        )
    }

    @Throws(Exception::class)
    private fun invokeHandler(handler: HandlerMethod, event: IntegrationEvent<*>) {
        executor.executeWithTranslation(
            {
                handler.method.invoke(handler.component, event.payload)
                logger.debug("[EventDispatcher] Handler executed: {}", handler.method.name)
            },
            { e, _ ->
                EventProcessingException(
                    CommonErrorCode.EVENT_HANDLER_ERROR,
                    e,
                    event.eventId,
                    event.eventType,
                )
            },
            TaskContext.of("EventDispatcher", "InvokeHandler", handler.method.name),
        )
    }

    fun getHandlerCount(): Int = handlers.values.sumOf { it.size }

    private data class HandlerMethod(
        val component: Any,
        val method: Method,
        val async: Boolean,
    ) {
        init {
            method.isAccessible = true
        }
    }

    @PreDestroy
    fun shutdown() = exec?.shutdown()
}

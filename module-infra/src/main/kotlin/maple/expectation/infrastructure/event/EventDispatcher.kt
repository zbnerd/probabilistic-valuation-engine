package maple.expectation.infrastructure.event

import maple.expectation.domain.event.IntegrationEvent
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.EventProcessingException
import maple.expectation.event.EventHandler
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Component
class EventDispatcher(
    private val executor: LogicExecutor,
    @Value("\${app.event.dispatcher.async:true}") private val enableAsync: Boolean
) {
    private val logger = LoggerFactory.getLogger(EventDispatcher::class.java)
    private val virtualThreadExecutor: Executor = if (enableAsync) Executors.newVirtualThreadPerTaskExecutor() else Executor { it.run() }
    private val handlers: MutableMap<Class<*>, MutableList<HandlerMethod>> = ConcurrentHashMap()

    init {
        logger.info(
            "[EventDispatcher] Initialized: async={}, executorType={}",
            enableAsync,
            if (enableAsync) "VirtualThreads" else "Synchronous"
        )
    }

    fun registerHandlers(component: Any) {
        executor.executeVoidJava(
            {
                try {
                    registerHandlersInternal(component)
                } catch (e: Exception) {
                    logger.error(
                        "[EventDispatcher] Handler registration failed for component: {}",
                        component.javaClass.simpleName,
                        e
                    )
                }
            },
            TaskContext.of("EventDispatcher", "RegisterHandlers", component.javaClass.simpleName)
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
                async
            )
        }

        if (registered > 0) {
            logger.info(
                "[EventDispatcher] Registered {} handlers from component: {}",
                registered,
                componentClass.simpleName
            )
        }
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

    fun dispatch(event: IntegrationEvent<*>) {
        executor.executeVoidJava(
            {
                try {
                    dispatchInternal(event)
                } catch (e: Exception) {
                    logger.error("[EventDispatcher] Dispatch failed for event: {}", event.eventType, e)
                }
            },
            TaskContext.of("EventDispatcher", "Dispatch", event.eventType)
        )
    }

    private fun dispatchInternal(event: IntegrationEvent<*>) {
        val eventType = event.payload!!.javaClass
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
                executor.executeVoidJava(
                    {
                        try {
                            invokeHandler(handler, event)
                        } catch (e: Exception) {
                            logger.error(
                                "[EventDispatcher] Async handler failed: method={}, eventType={}",
                                handler.method.name,
                                event.payload!!.javaClass.simpleName,
                                e
                            )
                        }
                    },
                    TaskContext.of("EventDispatcher", "InvokeAsync", handler.method.name)
                )
            }
        } else {
            executor.executeVoidJava(
                {
                    try {
                        invokeHandler(handler, event)
                    } catch (e: Exception) {
                        logger.error(
                            "[EventDispatcher] Sync handler failed: method={}, eventType={}",
                            handler.method.name,
                            event.payload!!.javaClass.simpleName,
                            e
                        )
                    }
                },
                TaskContext.of("EventDispatcher", "InvokeSync", handler.method.name)
            )
        }
    }

    @Throws(Exception::class)
    private fun invokeHandler(handler: HandlerMethod, event: IntegrationEvent<*>) {
        try {
            handler.method.invoke(handler.component, event.payload)
            logger.debug("[EventDispatcher] Handler executed: {}", handler.method.name)
        } catch (e: Exception) {
            logger.error(
                "[EventDispatcher] Handler failed: method={}, eventType={}, eventId={}",
                handler.method.name,
                event.eventType,
                event.eventId,
                e
            )
            throw EventProcessingException(
                CommonErrorCode.EVENT_HANDLER_ERROR,
                e,
                event.eventId,
                event.eventType
            )
        }
    }

    fun getHandlerCount(): Int = handlers.values.sumOf { it.size }

    private data class HandlerMethod(
        val component: Any,
        val method: Method,
        val async: Boolean
    ) {
        init {
            method.isAccessible = true
        }
    }
}

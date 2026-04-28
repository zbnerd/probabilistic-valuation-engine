package maple.expectation.infrastructure.mq.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.core.port.out.mq.MQTopicGroup
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

abstract class PgmqTopicGroup(
    private val pgmqClient: PgmqClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val queueMetrics: WorkerQueueMetrics,
    private val config: PgmqTopicConfig,
) : MQTopicGroup {

    private val log = LoggerFactory.getLogger(javaClass)
    private val metrics by lazy { queueMetrics.forQueue(name) }
    private val adapter by lazy { LegacyMessageAdapter(objectMapper) }
    private val handlerRef = AtomicReference<(IntegrationEvent<*>, MessageHandle) -> ConsumeResult>()

    override fun publish(message: IntegrationEvent<*>): MessageHandle {
        val context = TaskContext.of("PgmqTopic", "Publish", name)
        return executor.execute({
            val msgId = pgmqClient.send(name, message)
            MessageHandle(id = msgId, raw = msgId)
        }, context)
    }

    override fun subscribe(handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult) {
        handlerRef.set(handler)
    }

    @Scheduled(fixedDelayString = "\${pgmq.worker.common.polling-interval-ms:300}")
    fun pollLoop() {
        if (!lifecycleWrapper.beforeTask()) return
        val handler = handlerRef.get()
        if (handler == null) { lifecycleWrapper.afterTask(); return }

        val context = TaskContext.of("PgmqTopic", "Poll", name)
        executor.executeWithFinally({
            val messages = pgmqClient.read(name, Map::class.java, config.batchSize, config.visibilityTimeoutSec)
            metrics.updateQueueDepth(pgmqClient.queueLength(name))

            if (messages.isEmpty()) return@executeWithFinally

            messages.forEach { msg ->
                metrics.inflightIncrement()
                metrics.recordWaitDuration(msg.enqueuedAt)
            }

            messages.forEach { msg -> processMessage(msg, handler) }
        }, { lifecycleWrapper.afterTask() }, context)
    }

    private fun processMessage(
        msg: PgmqMessage<*>,
        handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult,
    ) {
        val context = TaskContext.of("PgmqTopic", "Process", name)
        val result = executor.executeOrDefault({
            if (msg.readCount > config.maxRetries) {
                log.warn("[{}] Max retries exceeded: msgId={}, readCount={}", name, msg.messageId, msg.readCount)
                return@executeOrDefault ConsumeResult.Fail
            }
            val envelope = adapter.adapt(msg.payload as Any, name)
            val handle = MessageHandle(id = msg.messageId, raw = msg)
            handler.invoke(envelope, handle)
        }, ConsumeResult.Retry(Duration.ofSeconds(30)), context)

        applyResult(msg, result)
        metrics.inflightDecrement()
    }

    private fun applyResult(msg: PgmqMessage<*>, result: ConsumeResult) {
        when (result) {
            is ConsumeResult.Ack -> {
                pgmqClient.archive(name, msg.messageId)
                metrics.success.increment()
            }
            is ConsumeResult.Retry -> {
                pgmqClient.setVisibilityTimeout(name, msg.messageId, result.delay.seconds)
                metrics.retry.increment()
            }
            is ConsumeResult.Fail -> {
                pgmqClient.archive(name, msg.messageId)
                metrics.failure.increment()
            }
        }
    }

    @PreDestroy
    fun onShutdown() {
        handlerRef.set(null)
    }
}

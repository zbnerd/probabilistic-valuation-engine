package maple.expectation.infrastructure.queue.strategy

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.queue.MessageQueueStrategy
import maple.expectation.infrastructure.queue.QueueMessage
import maple.expectation.infrastructure.queue.QueueType
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class InMemoryBufferStrategy<T>(
    private val meterRegistry: MeterRegistry,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
) : MessageQueueStrategy<T> {

    private val log = LoggerFactory.getLogger(InMemoryBufferStrategy::class.java)

    companion object {
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_MAX_QUEUE_SIZE = 10_000
    }

    private val mainQueue = ConcurrentLinkedQueue<QueueMessage<T>>()
    private val inflightMap = ConcurrentHashMap<String, QueueMessage<T>>()
    private val dlq = ConcurrentLinkedQueue<QueueMessage<T>>()

    private val pendingCount = AtomicInteger(0)
    private val inflightCount = AtomicInteger(0)
    private val dlqCount = AtomicInteger(0)

    @Volatile
    private var shuttingDown = false

    constructor(meterRegistry: MeterRegistry) : this(meterRegistry, DEFAULT_MAX_RETRIES, DEFAULT_MAX_QUEUE_SIZE)

    init {
        registerMetrics()
    }

    private fun registerMetrics() {
        val strategyTag = getType().name

        Gauge.builder("queue.pending", pendingCount) { it.get().toDouble() }
            .tag("strategy", strategyTag)
            .description("대기 중인 메시지 수")
            .register(meterRegistry)

        Gauge.builder("queue.inflight", inflightCount) { it.get().toDouble() }
            .tag("strategy", strategyTag)
            .description("처리 중인 메시지 수")
            .register(meterRegistry)

        Gauge.builder("queue.dlq", dlqCount) { it.get().toDouble() }
            .tag("strategy", strategyTag)
            .description("DLQ 메시지 수")
            .register(meterRegistry)
    }

    override fun publish(message: T): String? {
        if (shuttingDown) {
            meterRegistry.counter("queue.publish.rejected", "strategy", getType().name, "reason", "shutdown").increment()
            log.debug("[InMemoryBuffer] Rejected during shutdown")
            return null
        }

        if (pendingCount.get() >= maxQueueSize) {
            meterRegistry.counter("queue.publish.rejected", "strategy", getType().name, "reason", "backpressure").increment()
            log.warn("[InMemoryBuffer] Backpressure triggered: pending={}, max={}", pendingCount.get(), maxQueueSize)
            return null
        }

        val msgId = UUID.randomUUID().toString()
        val queueMessage = QueueMessage.of(msgId, message)

        mainQueue.offer(queueMessage)
        pendingCount.incrementAndGet()

        meterRegistry.counter("queue.publish.success", "strategy", getType().name).increment()
        log.debug("[InMemoryBuffer] Published message: msgId={}", msgId)

        return msgId
    }

    override fun consume(batchSize: Int): List<QueueMessage<T>> {
        val batch = mutableListOf<QueueMessage<T>>()

        for (i in 0 until batchSize) {
            val message = mainQueue.poll() ?: break

            inflightMap[message.msgId] = message
            pendingCount.decrementAndGet()
            inflightCount.incrementAndGet()

            batch.add(message)
        }

        if (batch.isNotEmpty()) {
            meterRegistry.counter("queue.consume.success", "strategy", getType().name).increment(batch.size.toDouble())
            log.debug("[InMemoryBuffer] Consumed {} messages", batch.size)
        }

        return batch
    }

    override fun ack(msgId: String) {
        val removed = inflightMap.remove(msgId)

        if (removed != null) {
            inflightCount.decrementAndGet()
            meterRegistry.counter("queue.ack.success", "strategy", getType().name).increment()
            log.debug("[InMemoryBuffer] ACK message: msgId={}", msgId)
        } else {
            meterRegistry.counter("queue.ack.not_found", "strategy", getType().name).increment()
            log.debug("[InMemoryBuffer] ACK message not found (already acked): msgId={}", msgId)
        }
    }

    override fun nack(msgId: String, retryCount: Int) {
        val message = inflightMap.remove(msgId)

        if (message == null) {
            meterRegistry.counter("queue.nack.not_found", "strategy", getType().name).increment()
            log.warn("[InMemoryBuffer] NACK message not found: msgId={}", msgId)
            return
        }

        inflightCount.decrementAndGet()

        if (retryCount >= maxRetries) {
            val dlqMessage = message.withRetryCount(retryCount)
            dlq.offer(dlqMessage)
            dlqCount.incrementAndGet()

            meterRegistry.counter("queue.nack.dlq", "strategy", getType().name).increment()
            log.warn("[InMemoryBuffer] Message moved to DLQ after {} retries: msgId={}", maxRetries, msgId)
        } else {
            val retryMessage = message.withIncrementedRetry()
            mainQueue.offer(retryMessage)
            pendingCount.incrementAndGet()

            meterRegistry.counter("queue.nack.retry", "strategy", getType().name).increment()
            log.debug("[InMemoryBuffer] Message scheduled for retry: msgId={}, retryCount={}", msgId, retryCount + 1)
        }
    }

    override fun getPendingCount(): Long = pendingCount.get().toLong()

    override fun getInflightCount(): Long = inflightCount.get().toLong()

    override fun getRetryCount(): Long = 0L

    override fun getDlqCount(): Long = dlqCount.get().toLong()

    override fun getType(): QueueType = QueueType.IN_MEMORY

    override fun isHealthy(): Boolean = !shuttingDown

    override fun prepareShutdown() {
        this.shuttingDown = true
        log.info("[InMemoryBuffer] Shutdown prepared - new publish will be rejected")
    }

    override fun isShuttingDown(): Boolean = shuttingDown

    fun isEmpty(): Boolean = mainQueue.isEmpty()

    fun pollDlq(maxCount: Int): List<QueueMessage<T>> {
        val batch = mutableListOf<QueueMessage<T>>()

        for (i in 0 until maxCount) {
            val message = dlq.poll() ?: break
            dlqCount.decrementAndGet()
            batch.add(message)
        }

        return batch
    }

    fun getMaxRetries(): Int = maxRetries

    fun getMaxQueueSize(): Int = maxQueueSize
}

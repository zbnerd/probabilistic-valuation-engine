package maple.expectation.infrastructure.queue.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Instant
import java.util.UUID
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.BufferLuaScripts
import maple.expectation.infrastructure.queue.MessageQueueStrategy
import maple.expectation.infrastructure.queue.QueueMessage
import maple.expectation.infrastructure.queue.QueueType
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

class RedisBufferStrategy<T>(
    private val redissonClient: RedissonClient,
    private val scriptProvider: BufferLuaScriptProvider,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val payloadType: Class<T>,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) : MessageQueueStrategy<T> {

    private val log = LoggerFactory.getLogger(RedisBufferStrategy::class.java)

    companion object {
        private const val DEFAULT_MAX_RETRIES = 3
        private const val MSG_ID_INDEX = 0
        private const val PAYLOAD_INDEX = 1
        private const val MIN_ENTRY_SIZE = 2
    }

    private val luaScriptExecutor = RedisLuaScriptExecutor(redissonClient, scriptProvider, executor)
    private val metricsManager = RedisQueueMetricsManager(meterRegistry)
    private val recoveryHandler = RedisQueueRecoveryHandler(
        redissonClient,
        scriptProvider,
        objectMapper,
        executor,
        meterRegistry,
        payloadType,
        metricsManager,
        QueueType.REDIS_LIST,
    )

    @Volatile
    private var shuttingDown = false

    init {
        metricsManager.registerMetrics(QueueType.REDIS_LIST)
        log.info("[RedisBufferStrategy] 초기화 완료 - maxRetries={}", maxRetries)
    }

    override fun publish(message: T): String? {
        if (shuttingDown) {
            meterRegistry.counter("queue.publish.rejected", "strategy", getType().name, "reason", "shutdown").increment()
            log.debug("[RedisBufferStrategy] Rejected during shutdown")
            return null
        }

        val sample = Timer.start(meterRegistry)
        val msgId = UUID.randomUUID().toString()

        val result = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getPublishSha() },
            BufferLuaScripts.PUBLISH,
            { sha -> scriptProvider.updatePublishSha(sha) },
            { sha -> executePublishScript(sha, msgId, message) },
            "Publish",
        )

        sample.stop(meterRegistry.timer("queue.publish.duration", "strategy", getType().name))

        if (result != null) {
            metricsManager.getCachedPendingCount().incrementAndGet()
            meterRegistry.counter("queue.publish.success", "strategy", getType().name).increment()
            log.debug("[RedisBufferStrategy] Published message: msgId={}", msgId)
        }

        return result
    }

    private fun executePublishScript(sha: String, msgId: String, message: T): String? = executor.executeOrDefault(
        {
            val payloadJson = objectMapper.writeValueAsString(
                PayloadWrapper(message, 0, Instant.now().toEpochMilli()),
            )
            luaScriptExecutor.executePublish(sha, msgId, payloadJson)
        },
        null,
        TaskContext.of("RedisBuffer", "Publish", msgId),
    )

    override fun consume(batchSize: Int): List<QueueMessage<T>> {
        val sample = Timer.start(meterRegistry)

        val result = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getConsumeSha() },
            BufferLuaScripts.CONSUME,
            { sha -> scriptProvider.updateConsumeSha(sha) },
            { sha -> executeConsumeScript(sha, batchSize) },
            "Consume",
        )

        sample.stop(meterRegistry.timer("queue.consume.duration", "strategy", getType().name))

        if (result.isNotEmpty()) {
            metricsManager.getCachedPendingCount().addAndGet(-result.size.toLong())
            metricsManager.getCachedInflightCount().addAndGet(result.size.toLong())
            meterRegistry.counter("queue.consume.success", "strategy", getType().name).increment(result.size.toDouble())
            log.debug("[RedisBufferStrategy] Consumed {} messages", result.size)
        }

        return result
    }

    private fun executeConsumeScript(sha: String, batchSize: Int): List<QueueMessage<T>> = executor.executeOrDefault(
        {
            val rawResult = luaScriptExecutor.executeConsume(sha, batchSize)
            convertToQueueMessages(rawResult)
        },
        emptyList(),
        TaskContext.of("RedisBuffer", "Consume", batchSize.toString()),
    )

    private fun convertToQueueMessages(rawResult: List<List<String>>): List<QueueMessage<T>> {
        val messages = mutableListOf<QueueMessage<T>>()

        for (entry in rawResult) {
            if (entry.size >= MIN_ENTRY_SIZE) {
                val msgId = entry[MSG_ID_INDEX]
                val payloadJson = entry[PAYLOAD_INDEX]

                val queueMessage = recoveryHandler.deserializePayload(msgId, payloadJson)
                if (queueMessage != null) {
                    messages.add(queueMessage)
                }
            }
        }

        return messages
    }

    override fun ack(msgId: String) {
        val sample = Timer.start(meterRegistry)

        val removed = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getAckSha() },
            BufferLuaScripts.ACK,
            { sha -> scriptProvider.updateAckSha(sha) },
            { sha -> luaScriptExecutor.executeAck(sha, msgId) },
            "Ack",
        )

        sample.stop(meterRegistry.timer("queue.ack.duration", "strategy", getType().name))

        if (removed != null && removed > 0) {
            metricsManager.getCachedInflightCount().decrementAndGet()
            meterRegistry.counter("queue.ack.success", "strategy", getType().name).increment()
            log.debug("[RedisBufferStrategy] ACK message: msgId={}", msgId)
        } else {
            meterRegistry.counter("queue.ack.not_found", "strategy", getType().name).increment()
            log.debug("[RedisBufferStrategy] ACK message not found (already acked): msgId={}", msgId)
        }
    }

    override fun nack(msgId: String, retryCount: Int) {
        val sample = Timer.start(meterRegistry)

        if (retryCount >= maxRetries) {
            scriptProvider.executeWithNoscriptHandling(
                { scriptProvider.getNackToDlqSha() },
                BufferLuaScripts.NACK_TO_DLQ,
                { sha -> scriptProvider.updateNackToDlqSha(sha) },
                { sha -> luaScriptExecutor.executeNackToDlq(sha, msgId) },
                "NackToDlq",
            )

            metricsManager.getCachedInflightCount().decrementAndGet()
            metricsManager.getCachedDlqCount().incrementAndGet()
            meterRegistry.counter("queue.nack.dlq", "strategy", getType().name).increment()
            log.warn("[RedisBufferStrategy] Message moved to DLQ after {} retries: msgId={}", maxRetries, msgId)
        } else {
            val nextAttemptAt = System.currentTimeMillis() + recoveryHandler.calculateBackoffDelay(retryCount)
            val currentPayloadJson = recoveryHandler.getPayload(msgId)

            if (currentPayloadJson == null) {
                log.warn("[RedisBufferStrategy] Payload not found for NACK: msgId={}", msgId)
                sample.stop(meterRegistry.timer("queue.nack.duration", "strategy", getType().name))
                return
            }

            val updatedPayloadJson = updateRetryCountInPayload(currentPayloadJson, retryCount)

            scriptProvider.executeWithNoscriptHandling(
                { scriptProvider.getNackToRetrySha() },
                BufferLuaScripts.NACK_TO_RETRY,
                { sha -> scriptProvider.updateNackToRetrySha(sha) },
                { sha ->
                    luaScriptExecutor.executeNackToRetry(
                        sha,
                        msgId,
                        nextAttemptAt,
                        retryCount + 1,
                        updatedPayloadJson,
                    )
                },
                "NackToRetry",
            )

            metricsManager.getCachedInflightCount().decrementAndGet()
            metricsManager.getCachedRetryCount().incrementAndGet()
            meterRegistry.counter("queue.nack.retry", "strategy", getType().name).increment()
            log.debug("[RedisBufferStrategy] Message scheduled for retry: msgId={}, retryCount={}", msgId, retryCount + 1)
        }

        sample.stop(meterRegistry.timer("queue.nack.duration", "strategy", getType().name))
    }

    private fun updateRetryCountInPayload(payloadJson: String, retryCount: Int): String = executor.executeOrDefault(
        {
            val wrapperType = objectMapper.typeFactory.constructParametricType(
                PayloadWrapper::class.java,
                payloadType,
            )
            val wrapper = objectMapper.readValue<PayloadWrapper<T>>(payloadJson, wrapperType)

            val updatedWrapper = PayloadWrapper(
                wrapper.payload,
                retryCount + 1,
                wrapper.createdAtMs,
            )

            objectMapper.writeValueAsString(updatedWrapper)
        },
        payloadJson,
        TaskContext.of("RedisBuffer", "UpdateRetryCount"),
    )

    override fun getPendingCount(): Long {
        refreshQueueCounts()
        return metricsManager.getCachedPendingCount().get()
    }

    override fun getInflightCount(): Long {
        refreshQueueCounts()
        return metricsManager.getCachedInflightCount().get()
    }

    override fun getRetryCount(): Long {
        refreshQueueCounts()
        return metricsManager.getCachedRetryCount().get()
    }

    override fun getDlqCount(): Long {
        refreshQueueCounts()
        return metricsManager.getCachedDlqCount().get()
    }

    private fun refreshQueueCounts() {
        scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getGetQueueCountsSha() },
            BufferLuaScripts.GET_QUEUE_COUNTS,
            { sha -> scriptProvider.updateGetQueueCountsSha(sha) },
            { executeGetQueueCountsScript(it) },
            "GetQueueCounts",
        )
    }

    private fun executeGetQueueCountsScript(sha: String): Boolean = executor.executeOrDefault(
        {
            val counts = luaScriptExecutor.executeGetQueueCounts(sha)

            if (counts != null && counts.size >= 4) {
                metricsManager.getCachedPendingCount().set(counts[MSG_ID_INDEX])
                metricsManager.getCachedInflightCount().set(counts[PAYLOAD_INDEX])
                metricsManager.getCachedRetryCount().set(counts[2])
                metricsManager.getCachedDlqCount().set(counts[3])
            }
            true
        },
        false,
        TaskContext.of("RedisBuffer", "GetQueueCounts"),
    )

    override fun getType(): QueueType = QueueType.REDIS_LIST

    override fun isHealthy(): Boolean {
        if (shuttingDown) return false

        return executor.executeOrDefault(
            {
                redissonClient.getBucket<Any>("health-check").isExists
                true
            },
            false,
            TaskContext.of("RedisBuffer", "HealthCheck"),
        )
    }

    override fun prepareShutdown() {
        this.shuttingDown = true
        log.info("[RedisBufferStrategy] Shutdown prepared - new publish will be rejected")
    }

    override fun isShuttingDown(): Boolean = shuttingDown

    fun getExpiredInflightMessages(timeoutMs: Long, limit: Int): List<String> = recoveryHandler.getExpiredInflightMessages(timeoutMs, limit)

    fun redrive(msgId: String): Boolean = recoveryHandler.redrive(msgId)

    fun processRetryQueue(limit: Int): List<String> = recoveryHandler.processRetryQueue(limit)

    fun pollDlq(maxCount: Int): List<QueueMessage<T>> = recoveryHandler.pollDlq(maxCount)

    fun getMaxRetries(): Int = maxRetries

    private data class PayloadWrapper<T>(
        val payload: T,
        val retryCount: Int,
        val createdAtMs: Long,
    )
}

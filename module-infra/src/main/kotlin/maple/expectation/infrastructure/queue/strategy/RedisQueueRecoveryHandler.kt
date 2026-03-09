package maple.expectation.infrastructure.queue.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.BufferLuaScripts
import maple.expectation.infrastructure.queue.QueueMessage
import maple.expectation.infrastructure.queue.QueueType
import maple.expectation.infrastructure.queue.RedisKey
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider
import org.redisson.api.RDeque
import org.redisson.api.RMap
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory

class RedisQueueRecoveryHandler<T>(
    private val redissonClient: RedissonClient,
    private val scriptProvider: BufferLuaScriptProvider,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val payloadType: Class<T>,
    private val metricsManager: RedisQueueMetricsManager,
    private val queueType: QueueType,
) {
    private val log = LoggerFactory.getLogger(RedisQueueRecoveryHandler::class.java)

    private val inflightTsKey: String = RedisKey.EXPECTATION_BUFFER_INFLIGHT_TS.key
    private val inflightKey: String = RedisKey.EXPECTATION_BUFFER_INFLIGHT.key
    private val mainQueueKey: String = RedisKey.EXPECTATION_BUFFER.key
    private val retryKey: String = RedisKey.EXPECTATION_BUFFER_RETRY.key
    private val dlqKey: String = RedisKey.EXPECTATION_BUFFER_DLQ.key
    private val payloadKey: String = RedisKey.EXPECTATION_BUFFER_PAYLOAD.key

    companion object {
        private const val BASE_RETRY_DELAY_MS = 1000L
    }

    fun getExpiredInflightMessages(timeoutMs: Long, limit: Int): List<String> {
        val maxTimestamp = System.currentTimeMillis() - timeoutMs

        return scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getGetExpiredInflightSha() },
            BufferLuaScripts.GET_EXPIRED_INFLIGHT,
            { sha -> scriptProvider.updateGetExpiredInflightSha(sha) },
            { sha -> executeGetExpiredInflightScript(sha, maxTimestamp, limit) },
            "GetExpiredInflight",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeGetExpiredInflightScript(sha: String, maxTimestamp: Long, limit: Int): List<String> = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            script.evalSha(
                RScript.Mode.READ_ONLY,
                sha,
                RScript.ReturnType.MULTI,
                listOf(inflightTsKey),
                maxTimestamp.toString(),
                limit.toString(),
            ) as? List<String> ?: emptyList()
        },
        emptyList(),
        TaskContext.of("RedisBuffer", "GetExpiredInflight"),
    )

    fun redrive(msgId: String): Boolean {
        val result = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getRedriveSha() },
            BufferLuaScripts.REDRIVE,
            { sha -> scriptProvider.updateRedriveSha(sha) },
            { sha -> executeRedriveScript(sha, msgId) },
            "Redrive",
        )

        if (result != null && result > 0) {
            metricsManager.getCachedInflightCount().decrementAndGet()
            metricsManager.getCachedPendingCount().incrementAndGet()
            meterRegistry.counter("queue.redrive.success", "strategy", queueType.name).increment()
            log.info("[RedisBufferStrategy] Message redriven: msgId={}", msgId)
            return true
        } else {
            meterRegistry.counter("queue.redrive.skip", "strategy", queueType.name).increment()
            log.debug("[RedisBufferStrategy] Redrive skipped (already acked): msgId={}", msgId)
            return false
        }
    }

    private fun executeRedriveScript(sha: String, msgId: String): Long = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            @Suppress("UNCHECKED_CAST")
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.INTEGER,
                listOf(inflightKey, inflightTsKey, mainQueueKey),
                msgId,
            ) as Long
        },
        0L,
        TaskContext.of("RedisBuffer", "Redrive", msgId),
    )

    fun processRetryQueue(limit: Int): List<String> {
        val now = System.currentTimeMillis()

        @Suppress("UNCHECKED_CAST")
        val processed = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.getProcessRetryQueueSha() },
            BufferLuaScripts.PROCESS_RETRY_QUEUE,
            { sha -> scriptProvider.updateProcessRetryQueueSha(sha) },
            { sha -> executeProcessRetryQueueScript(sha, now, limit) },
            "ProcessRetryQueue",
        ) as? List<String> ?: emptyList()

        if (processed.isNotEmpty()) {
            metricsManager.getCachedRetryCount().addAndGet(-processed.size.toLong())
            metricsManager.getCachedPendingCount().addAndGet(processed.size.toLong())
            meterRegistry.counter("queue.retry.processed", "strategy", queueType.name).increment(processed.size.toDouble())
            log.info("[RedisBufferStrategy] Processed {} retry messages", processed.size)
        }

        return processed
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeProcessRetryQueueScript(sha: String, now: Long, limit: Int): List<String> = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                listOf(retryKey, mainQueueKey),
                now.toString(),
                limit.toString(),
            ) as? List<String> ?: emptyList()
        },
        emptyList(),
        TaskContext.of("RedisBuffer", "ProcessRetryQueue"),
    )

    fun pollDlq(maxCount: Int): List<QueueMessage<T>> {
        val messages = mutableListOf<QueueMessage<T>>()

        return executor.executeOrDefault(
            {
                val deque: RDeque<String> = redissonClient.getDeque(dlqKey, StringCodec.INSTANCE)
                for (i in 0 until maxCount) {
                    val msgId = deque.pollFirst() ?: break
                    val payloadJson = getPayload(msgId)
                    val queueMessage = deserializePayload(msgId, payloadJson)
                    if (queueMessage != null) {
                        messages.add(queueMessage)
                        metricsManager.getCachedDlqCount().decrementAndGet()
                    }
                }
                messages
            },
            messages,
            TaskContext.of("RedisBuffer", "PollDlq"),
        )
    }

    fun getPayload(msgId: String): String? = executor.executeOrDefault(
        {
            val map: RMap<String, String> = redissonClient.getMap(payloadKey, StringCodec.INSTANCE)
            map.get(msgId)
        },
        null,
        TaskContext.of("RedisBuffer", "GetPayload", msgId),
    )

    fun deserializePayload(msgId: String, payloadJson: String?): QueueMessage<T>? {
        if (payloadJson == null) return null

        return executor.executeOrDefault(
            {
                val wrapperType = objectMapper.typeFactory.constructParametricType(
                    PayloadWrapper::class.java,
                    payloadType,
                )
                val wrapper = objectMapper.readValue<PayloadWrapper<T>>(payloadJson, wrapperType)

                QueueMessage(
                    msgId,
                    wrapper.payload,
                    wrapper.retryCount,
                    Instant.ofEpochMilli(wrapper.createdAtMs),
                )
            },
            null,
            TaskContext.of("RedisBuffer", "Deserialize", msgId),
        )
    }

    fun calculateBackoffDelay(retryCount: Int): Long = BASE_RETRY_DELAY_MS * (1L shl retryCount)

    private data class PayloadWrapper<T>(
        val payload: T,
        val retryCount: Int,
        val createdAtMs: Long,
    )
}

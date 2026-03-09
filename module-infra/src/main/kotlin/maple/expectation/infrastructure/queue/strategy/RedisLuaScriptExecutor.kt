package maple.expectation.infrastructure.queue.strategy

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory

class RedisLuaScriptExecutor(
    private val redissonClient: RedissonClient,
    private val scriptProvider: BufferLuaScriptProvider,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(RedisLuaScriptExecutor::class.java)

    private val mainQueueKey: String = RedisKey.EXPECTATION_BUFFER.key
    private val inflightKey: String = RedisKey.EXPECTATION_BUFFER_INFLIGHT.key
    private val inflightTsKey: String = RedisKey.EXPECTATION_BUFFER_INFLIGHT_TS.key
    private val payloadKey: String = RedisKey.EXPECTATION_BUFFER_PAYLOAD.key
    private val retryKey: String = RedisKey.EXPECTATION_BUFFER_RETRY.key
    private val dlqKey: String = RedisKey.EXPECTATION_BUFFER_DLQ.key

    companion object {
        private const val MSG_ID_INDEX = 0
        private const val PAYLOAD_INDEX = 1
        private const val MIN_ENTRY_SIZE = 2
    }

    fun executePublish(sha: String, msgId: String, payloadJson: String): String = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            @Suppress("UNCHECKED_CAST")
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.INTEGER,
                listOf(mainQueueKey, payloadKey),
                msgId,
                payloadJson,
            ) as Long
            msgId
        },
        "",
        TaskContext.of("RedisBuffer", "Publish", msgId),
    )

    @Suppress("UNCHECKED_CAST")
    fun executeConsume(sha: String, batchSize: Int): List<List<String>> = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            val timestamp = System.currentTimeMillis()

            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                listOf(mainQueueKey, inflightKey, inflightTsKey, payloadKey),
                batchSize.toString(),
                timestamp.toString(),
            ) as? List<List<String>> ?: emptyList()
        },
        emptyList(),
        TaskContext.of("RedisBuffer", "Consume", batchSize.toString()),
    )

    fun executeAck(sha: String, msgId: String): Long = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            @Suppress("UNCHECKED_CAST")
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.INTEGER,
                listOf(inflightKey, inflightTsKey, payloadKey),
                msgId,
            ) as Long
        },
        0L,
        TaskContext.of("RedisBuffer", "Ack", msgId),
    )

    fun executeNackToDlq(sha: String, msgId: String): Long = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            @Suppress("UNCHECKED_CAST")
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.INTEGER,
                listOf(inflightKey, inflightTsKey, dlqKey),
                msgId,
            ) as Long
        },
        0L,
        TaskContext.of("RedisBuffer", "NackToDlq", msgId),
    )

    fun executeNackToRetry(
        sha: String,
        msgId: String,
        nextAttemptAt: Long,
        retryCount: Int,
        updatedPayloadJson: String,
    ): Long = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            @Suppress("UNCHECKED_CAST")
            script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.INTEGER,
                listOf(inflightKey, inflightTsKey, retryKey, payloadKey),
                msgId,
                nextAttemptAt.toString(),
                retryCount.toString(),
                updatedPayloadJson,
            ) as Long
        },
        0L,
        TaskContext.of("RedisBuffer", "NackToRetry", msgId),
    )

    @Suppress("UNCHECKED_CAST")
    fun executeGetQueueCounts(sha: String): List<Long> = executor.executeOrDefault(
        {
            val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
            val counts = script.evalSha(
                RScript.Mode.READ_ONLY,
                sha,
                RScript.ReturnType.MULTI,
                listOf(mainQueueKey, inflightKey, retryKey, dlqKey),
            ) as? List<Long>

            counts ?: listOf(0L, 0L, 0L, 0L)
        },
        listOf(0L, 0L, 0L, 0L),
        TaskContext.of("RedisBuffer", "GetQueueCounts"),
    )

    fun extractMessageIdsFromConsumeResult(rawResult: List<List<String>>): List<String> {
        val msgIds = mutableListOf<String>()
        for (entry in rawResult) {
            if (entry.size >= MIN_ENTRY_SIZE) {
                msgIds.add(entry[MSG_ID_INDEX])
            }
        }
        return msgIds
    }
}

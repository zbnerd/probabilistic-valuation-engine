package maple.expectation.infrastructure.queue.script

import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.error.exception.RedisScriptExecutionException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.BufferLuaScripts
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Buffer Lua Script SHA 캐싱 및 NOSCRIPT 에러 핸들링 제공자
 */
@Component
class BufferLuaScriptProvider(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(BufferLuaScriptProvider::class.java)

    companion object {
        private const val NOSCRIPT_ERROR_PREFIX = "NOSCRIPT"
    }

    private val publishShaRef = AtomicReference<String>()
    private val consumeShaRef = AtomicReference<String>()
    private val ackShaRef = AtomicReference<String>()
    private val nackToRetryShaRef = AtomicReference<String>()
    private val nackToDlqShaRef = AtomicReference<String>()
    private val redriveShaRef = AtomicReference<String>()
    private val processRetryQueueShaRef = AtomicReference<String>()
    private val getExpiredInflightShaRef = AtomicReference<String>()
    private val getQueueCountsShaRef = AtomicReference<String>()

    @PostConstruct
    fun loadScripts() {
        val loaded = executor.executeOrDefault(
            {
                val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)

                publishShaRef.set(script.scriptLoad(BufferLuaScripts.PUBLISH))
                consumeShaRef.set(script.scriptLoad(BufferLuaScripts.CONSUME))
                ackShaRef.set(script.scriptLoad(BufferLuaScripts.ACK))
                nackToRetryShaRef.set(script.scriptLoad(BufferLuaScripts.NACK_TO_RETRY))
                nackToDlqShaRef.set(script.scriptLoad(BufferLuaScripts.NACK_TO_DLQ))
                redriveShaRef.set(script.scriptLoad(BufferLuaScripts.REDRIVE))
                processRetryQueueShaRef.set(script.scriptLoad(BufferLuaScripts.PROCESS_RETRY_QUEUE))
                getExpiredInflightShaRef.set(script.scriptLoad(BufferLuaScripts.GET_EXPIRED_INFLIGHT))
                getQueueCountsShaRef.set(script.scriptLoad(BufferLuaScripts.GET_QUEUE_COUNTS))

                log.info("[BufferLuaScriptProvider] SHA 캐싱 완료 - 9개 스크립트 로드")
                true
            },
            false,
            TaskContext.of("BufferLuaScript", "LoadAll"),
        )

        if (!loaded) {
            log.warn("[BufferLuaScriptProvider] 시작 시 스크립트 로드 실패 - 첫 호출 시 Lazy Loading 시도")
        }
    }

    fun getPublishSha(): String = publishShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.PUBLISH, "Publish") }
    fun getConsumeSha(): String = consumeShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.CONSUME, "Consume") }
    fun getAckSha(): String = ackShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.ACK, "Ack") }
    fun getNackToRetrySha(): String = nackToRetryShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.NACK_TO_RETRY, "NackToRetry") }
    fun getNackToDlqSha(): String = nackToDlqShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.NACK_TO_DLQ, "NackToDlq") }
    fun getRedriveSha(): String = redriveShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.REDRIVE, "Redrive") }
    fun getProcessRetryQueueSha(): String = processRetryQueueShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.PROCESS_RETRY_QUEUE, "ProcessRetryQueue") }
    fun getGetExpiredInflightSha(): String = getExpiredInflightShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.GET_EXPIRED_INFLIGHT, "GetExpiredInflight") }
    fun getGetQueueCountsSha(): String = getQueueCountsShaRef.updateAndGet { it ?: reloadScript(BufferLuaScripts.GET_QUEUE_COUNTS, "GetQueueCounts") }

    fun updatePublishSha(sha: String) {
        publishShaRef.set(sha)
    }
    fun updateConsumeSha(sha: String) {
        consumeShaRef.set(sha)
    }
    fun updateAckSha(sha: String) {
        ackShaRef.set(sha)
    }
    fun updateNackToRetrySha(sha: String) {
        nackToRetryShaRef.set(sha)
    }
    fun updateNackToDlqSha(sha: String) {
        nackToDlqShaRef.set(sha)
    }
    fun updateRedriveSha(sha: String) {
        redriveShaRef.set(sha)
    }
    fun updateProcessRetryQueueSha(sha: String) {
        processRetryQueueShaRef.set(sha)
    }
    fun updateGetExpiredInflightSha(sha: String) {
        getExpiredInflightShaRef.set(sha)
    }
    fun updateGetQueueCountsSha(sha: String) {
        getQueueCountsShaRef.set(sha)
    }

    fun <T> executeWithNoscriptHandling(
        shaGetter: () -> String,
        scriptSource: String,
        shaUpdater: (String) -> Unit,
        scriptExecutor: (String) -> T,
        scriptName: String,
    ): T = executor.executeOrCatch(
        { scriptExecutor(shaGetter()) },
        { e -> handleNoscriptAndRetry(e, scriptSource, shaUpdater, scriptExecutor, scriptName) },
        TaskContext.of("BufferLuaScript", "Execute", scriptName),
    )

    private fun <T> handleNoscriptAndRetry(
        e: Throwable,
        scriptSource: String,
        shaUpdater: (String) -> Unit,
        scriptExecutor: (String) -> T,
        scriptName: String,
    ): T {
        if (!isNoscriptError(e)) {
            throw RedisScriptExecutionException(scriptName, e)
        }

        log.warn("[NOSCRIPT] Buffer 스크립트 재로드 필요: {}", scriptName)
        val newSha = reloadScript(scriptSource, scriptName)
        shaUpdater(newSha)

        return scriptExecutor(newSha)
    }

    private fun isNoscriptError(e: Throwable): Boolean {
        val message = e.message
        if (message != null && message.contains(NOSCRIPT_ERROR_PREFIX)) {
            return true
        }
        val cause = e.cause
        return cause != null && isNoscriptError(cause)
    }

    private fun reloadScript(scriptSource: String, scriptName: String): String {
        val script: RScript = redissonClient.getScript(StringCodec.INSTANCE)
        val sha = script.scriptLoad(scriptSource)
        log.info("[BufferLuaScriptProvider] 스크립트 재로드 완료 - {}: {}", scriptName, sha)
        return sha
    }
}

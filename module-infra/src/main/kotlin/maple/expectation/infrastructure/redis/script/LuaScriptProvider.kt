package maple.expectation.infrastructure.redis.script

import maple.expectation.error.exception.RedisScriptExecutionException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import jakarta.annotation.PostConstruct

@Component
class LuaScriptProvider(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor
) {
    private val logger = LoggerFactory.getLogger(LuaScriptProvider::class.java)

    private val transferShaRef = AtomicReference<String>()
    private val deleteAndDecrementShaRef = AtomicReference<String>()
    private val compensationShaRef = AtomicReference<String>()

    companion object {
        private const val NOSCRIPT_ERROR_PREFIX = "NOSCRIPT"
    }

    @PostConstruct
    fun loadScripts() {
        val loaded = executor.executeOrDefault(
            {
                val script = redissonClient.getScript(StringCodec.INSTANCE)

                val transferSha = script.scriptLoad(LuaScripts.ATOMIC_TRANSFER)
                transferShaRef.set(transferSha)

                val deleteAndDecrementSha = script.scriptLoad(LuaScripts.ATOMIC_DELETE_AND_DECREMENT)
                deleteAndDecrementShaRef.set(deleteAndDecrementSha)

                val compensationSha = script.scriptLoad(LuaScripts.ATOMIC_COMPENSATION)
                compensationShaRef.set(compensationSha)

                logger.info(
                    "✅ [LuaScriptProvider] SHA 캐싱 완료 - Transfer: {}, DeleteDecr: {}, Compensation: {}",
                    transferSha,
                    deleteAndDecrementSha,
                    compensationSha
                )
                true
            },
            false,
            TaskContext.of("LuaScript", "LoadAll")
        )

        if (!loaded) {
            logger.warn("⚠️ [LuaScriptProvider] 시작 시 스크립트 로드 실패 - 첫 호출 시 Lazy Loading 시도")
        }
    }

    val transferSha: String
        get() = transferShaRef.updateAndGet { current -> current ?: reloadScript(LuaScripts.ATOMIC_TRANSFER, "Transfer") }

    val deleteAndDecrementSha: String
        get() = deleteAndDecrementShaRef.updateAndGet { current -> current ?: reloadScript(LuaScripts.ATOMIC_DELETE_AND_DECREMENT, "DeleteAndDecrement") }

    val compensationSha: String
        get() = compensationShaRef.updateAndGet { current -> current ?: reloadScript(LuaScripts.ATOMIC_COMPENSATION, "Compensation") }

    fun <T> executeWithNoscriptHandling(
        shaGetter: () -> String,
        scriptSource: String,
        shaUpdater: (String) -> Unit,
        scriptExecutor: (String) -> T,
        scriptName: String
    ): T {
        return executor.executeWithFallback(
            { scriptExecutor(shaGetter()) },
            { e -> handleNoscriptAndRetry(e, scriptSource, shaUpdater, scriptExecutor, scriptName) },
            TaskContext.of("LuaScript", "Execute", scriptName)
        )
    }

    private fun <T> handleNoscriptAndRetry(
        e: Throwable,
        scriptSource: String,
        shaUpdater: (String) -> Unit,
        scriptExecutor: (String) -> T,
        scriptName: String
    ): T {
        if (!isNoscriptError(e)) {
            throw RedisScriptExecutionException(scriptName, e)
        }

        logger.warn("⚠️ [NOSCRIPT] 스크립트 재로드 필요: {}", scriptName)
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
        val script = redissonClient.getScript(StringCodec.INSTANCE)
        val sha = script.scriptLoad(scriptSource)
        logger.info("🔄 [LuaScriptProvider] 스크립트 재로드 완료 - {}: {}", scriptName, sha)
        return sha
    }

    fun updateTransferSha(sha: String) {
        transferShaRef.set(sha)
    }

    fun updateDeleteAndDecrementSha(sha: String) {
        deleteAndDecrementShaRef.set(sha)
    }

    fun updateCompensationSha(sha: String) {
        compensationShaRef.set(sha)
    }
}

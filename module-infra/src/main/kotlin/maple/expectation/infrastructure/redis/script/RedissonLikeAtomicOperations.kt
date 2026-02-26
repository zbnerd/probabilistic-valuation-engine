package maple.expectation.infrastructure.redis.script

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Arrays

@Component
class RedissonLikeAtomicOperations(
    private val redissonClient: RedissonClient,
    private val scriptProvider: LuaScriptProvider,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeAtomicOperations {

    private val logger = LoggerFactory.getLogger(RedissonLikeAtomicOperations::class.java)

    override fun atomicTransfer(userIgn: String, count: Long): Boolean {
        validateInput(userIgn, count)

        val sample = Timer.start(meterRegistry)
        return executor.executeWithFinally(
            {
                scriptProvider.executeWithNoscriptHandling(
                    { scriptProvider.compensationSha },
                    LuaScripts.ATOMIC_TRANSFER,
                    { scriptProvider.updateTransferSha(it) },
                    { sha -> executeTransferScript(sha, userIgn, count) },
                    "Transfer"
                )
            },
            { stopTimer(sample, "transfer") },
            TaskContext.of("AtomicOps", "TransferTimed", userIgn)
        )
    }

    override fun atomicDeleteAndDecrement(tempKey: String, userIgn: String, count: Long): Long {
        validateTempKey(tempKey)
        validateInput(userIgn, count)

        val sample = Timer.start(meterRegistry)
        return executor.executeWithFinally(
            {
                scriptProvider.executeWithNoscriptHandling(
                    { scriptProvider.compensationSha },
                    LuaScripts.ATOMIC_DELETE_AND_DECREMENT,
                    { scriptProvider.updateDeleteAndDecrementSha(it) },
                    { sha -> executeDeleteAndDecrementScript(sha, tempKey, userIgn, count) },
                    "DeleteAndDecrement"
                )
            },
            { stopTimer(sample, "deleteAndDecrement") },
            TaskContext.of("AtomicOps", "DeleteAndDecrementTimed", userIgn)
        )
    }

    override fun atomicCompensation(tempKey: String, userIgn: String, count: Long): Boolean {
        validateTempKey(tempKey)
        validateInput(userIgn, count)

        val sample = Timer.start(meterRegistry)
        return executor.executeWithFinally(
            { executeCompensationWithMetrics(tempKey, userIgn, count) },
            { stopTimer(sample, "compensation") },
            TaskContext.of("AtomicOps", "CompensationTimed", userIgn)
        )
    }

    private fun executeCompensationWithMetrics(tempKey: String, userIgn: String, count: Long): Boolean {
        val result = scriptProvider.executeWithNoscriptHandling(
            { scriptProvider.compensationSha },
            LuaScripts.ATOMIC_COMPENSATION,
            { scriptProvider.updateCompensationSha(it) },
            { sha -> executeCompensationScript(sha, tempKey, userIgn, count) },
            "Compensation"
        )

        if (result) {
            meterRegistry.counter("like.sync.compensation.count").increment()
            logger.info("♻️ [Compensation] 복구 완료: {} ({}건)", userIgn, count)
        }

        return result
    }

    private fun executeTransferScript(sha: String, userIgn: String, count: Long): Boolean {
        return executor.executeOrDefault(
            {
                val script = redissonClient.getScript(StringCodec.INSTANCE)
                val result = script.evalSha<Any>(
                    RScript.Mode.READ_WRITE,
                    sha,
                    RScript.ReturnType.INTEGER,
                    listOf(LuaScripts.Keys.HASH, LuaScripts.Keys.TOTAL_COUNT),
                    userIgn,
                    count.toString()
                )
                result != null && result == 1L
            },
            false,
            TaskContext.of("AtomicOps", "Transfer", userIgn)
        )
    }

    private fun executeDeleteAndDecrementScript(sha: String, tempKey: String, userIgn: String, count: Long): Long {
        return executor.executeOrDefault(
            {
                val script = redissonClient.getScript(StringCodec.INSTANCE)
                val result = script.evalSha<Any>(
                    RScript.Mode.READ_WRITE,
                    sha,
                    RScript.ReturnType.INTEGER,
                    listOf(tempKey, LuaScripts.Keys.TOTAL_COUNT),
                    userIgn,
                    count.toString()
                )
                (result as? Long) ?: 0L
            },
            0L,
            TaskContext.of("AtomicOps", "DeleteAndDecrement", userIgn)
        )
    }

    private fun executeCompensationScript(sha: String, tempKey: String, userIgn: String, count: Long): Boolean {
        return executor.executeOrDefault(
            {
                val script = redissonClient.getScript(StringCodec.INSTANCE)
                val result = script.evalSha<Any>(
                    RScript.Mode.READ_WRITE,
                    sha,
                    RScript.ReturnType.INTEGER,
                    listOf(LuaScripts.Keys.HASH, tempKey),
                    userIgn,
                    count.toString()
                )
                result != null && result == 1L
            },
            false,
            TaskContext.of("AtomicOps", "Compensation", userIgn)
        )
    }

    private fun stopTimer(sample: Timer.Sample, scriptName: String) {
        sample.stop(meterRegistry.timer("like.sync.lua.duration", "script", scriptName))
    }

    private fun validateInput(userIgn: String, count: Long) {
        requireNotNull(userIgn) { "userIgn must not be null" }
        require(userIgn.isNotBlank()) { "userIgn must not be blank" }
        require(count > 0 && count <= MAX_INCREMENT_PER_OPERATION) {
            "count must be between 1 and $MAX_INCREMENT_PER_OPERATION, but was: $count"
        }
    }

    private fun validateTempKey(tempKey: String) {
        requireNotNull(tempKey) { "tempKey must not be null" }
        require(tempKey.isNotBlank()) { "tempKey must not be blank" }
    }
}

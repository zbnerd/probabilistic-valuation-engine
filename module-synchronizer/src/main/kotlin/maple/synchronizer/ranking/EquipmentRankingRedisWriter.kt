package maple.synchronizer.ranking

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.preparer.PreppedDocument
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class EquipmentRankingRedisWriter(
    private val redisTemplate: StringRedisTemplate,
    private val properties: EquipmentRankingProperties,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun update(documents: List<PreppedDocument>) {
        if (!properties.enabled || documents.isEmpty()) return

        val rankable = documents.filter { it.userIgn?.isNotBlank() == true }
        if (rankable.isEmpty()) return

        val updated = executor.executeOrDefault(
            {
                rankable
                    .groupBy { it.presetNo.toInt() }
                    .values
                    .sumOf(::updatePreset)
            },
            0,
            TaskContext.of("Synchronizer", "UpdateEquipmentRanking"),
        )

        log.debug("Equipment ranking Redis update: attempted={} updated={}", rankable.size, updated)
    }

    private fun updatePreset(documents: List<PreppedDocument>): Int {
        var updated = 0
        documents.chunked(properties.batchSize.coerceAtLeast(1)).forEach { batch ->
            redisTemplate.executePipelined { connection ->
                val key = rankingKey(documents.first().presetNo.toInt()).toByteArray(StandardCharsets.UTF_8)
                batch.forEach { document ->
                    val userIgn = document.userIgn ?: return@forEach
                    connection.zSetCommands().zAdd(
                        key,
                        document.totalCost.toDouble(),
                        userIgn.toByteArray(StandardCharsets.UTF_8),
                    )
                    updated += 1
                }
                connection.zSetCommands().zRemRange(key, 0, -properties.topSize.coerceAtLeast(1).toLong() - 1)
                null
            }
        }
        return updated
    }

    private fun rankingKey(presetNo: Int): String =
        "${properties.keyPrefix}:preset:$presetNo"
}

package maple.restcontroller.ranking

import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

class EquipmentRankingCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun topByTotalCost(presetNo: Int, limit: Int): List<EquipmentRankingEntry>? {
        val entries = runCatching {
            redisTemplate.opsForZSet()
                .reverseRangeWithScores(rankingKey(presetNo), 0, (limit - 1).toLong())
                ?.mapIndexedNotNull { index, tuple ->
                    val userIgn = tuple.value ?: return@mapIndexedNotNull null
                    val score = tuple.score ?: return@mapIndexedNotNull null
                    EquipmentRankingEntry(
                        rank = index + 1,
                        userIgn = userIgn,
                        presetNo = presetNo,
                        totalCost = score.toLong(),
                    )
                }
                .orEmpty()
        }.getOrElse { error ->
            log.warn("Equipment ranking Redis read failed: presetNo={} limit={} error={}", presetNo, limit, error.javaClass.simpleName)
            return null
        }

        if (entries.isEmpty()) {
            log.debug("Equipment ranking Redis miss: presetNo={} limit={}", presetNo, limit)
        }
        return entries
    }

    private fun rankingKey(presetNo: Int): String = "${properties.ranking.redisKeyPrefix}:preset:$presetNo"
}

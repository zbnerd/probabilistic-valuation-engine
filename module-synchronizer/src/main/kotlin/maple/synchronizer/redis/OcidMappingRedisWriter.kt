package maple.synchronizer.redis

import maple.synchronizer.domain.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class OcidMappingRedisWriter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        val tempKey = "$REDIS_KEY:tmp:${System.nanoTime()}"
        redisTemplate.executePipelined { connection ->
            for (mapping in mappings) {
                connection.hashCommands().hSet(
                    tempKey.toByteArray(),
                    mapping.userIgn.toByteArray(),
                    mapping.ocid.toByteArray(),
                )
            }
            null
        }
        redisTemplate.rename(tempKey, REDIS_KEY)
        log.info("[OcidMapping] Redis written atomically via RENAME: {} mappings to {}", mappings.size, REDIS_KEY)
    }
}

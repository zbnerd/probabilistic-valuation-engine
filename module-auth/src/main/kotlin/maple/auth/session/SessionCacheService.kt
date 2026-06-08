package maple.auth.session

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import maple.expectation.core.domain.auth.Session
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class SessionCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.session.ttl-seconds:3600}") private val ttlSeconds: Long,
) {
    fun findByFingerprint(fingerprint: String): Session? {
        val key = "session:fp:$fingerprint"
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return runCatching { objectMapper.readValue(json, Session::class.java) }
            .getOrElse {
                log.warn("[SessionCache] deserialization failed for key={}", key)
                null
            }
    }

    fun save(session: Session) {
        val key = "session:fp:${session.fingerprint}"
        val json = objectMapper.writeValueAsString(session)
        redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds))
        log.debug("[SessionCache] saved: fingerprint={}, ttl={}s", session.fingerprint, ttlSeconds)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SessionCacheService::class.java)
    }
}

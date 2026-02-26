package maple.expectation.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.domain.Session
import maple.expectation.domain.repository.RedisSessionRepository as DomainRedisSessionRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.jspecify.annotations.Nullable
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

@Repository
open class RedisSessionRepositoryImpl(
    private val redissonClient: RedissonClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    @Value("\${auth.session.ttl}") private val sessionTtlSeconds: Long,
) : DomainRedisSessionRepository {

    private companion object {
        const val SESSION_KEY_PREFIX = "session:"
        const val FIELD_FINGERPRINT = "fingerprint"
        const val FIELD_USER_IGN = "userIgn"
        const val FIELD_ACCOUNT_ID = "accountId"
        const val FIELD_API_KEY = "apiKey"
        const val FIELD_MY_OCIDS = "myOcids"
        const val FIELD_ROLE = "role"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_LAST_ACCESSED_AT = "lastAccessedAt"
    }

    override fun save(session: Session) {
        val key = buildKey(session.sessionId())
        val map = redissonClient.getMap<String, String>(key)

        executor.executeVoidJava(
            Runnable {
                map[FIELD_FINGERPRINT] = session.fingerprint()
                map[FIELD_USER_IGN] = session.userIgn()
                map[FIELD_ACCOUNT_ID] = session.accountId()
                map[FIELD_API_KEY] = session.apiKey()
                map[FIELD_MY_OCIDS] = serializeOcids(session.myOcids())
                map[FIELD_ROLE] = session.role()
                map[FIELD_CREATED_AT] = session.createdAt().toString()
                map[FIELD_LAST_ACCESSED_AT] = session.lastAccessedAt().toString()
                map.expire(Duration.ofSeconds(sessionTtlSeconds))
            },
            TaskContext.of("Session", "Save", session.sessionId()),
        )
    }

    override fun findById(sessionId: String): Session? {
        return executor.executeOrDefault(
            { doFindById(sessionId) },
            null,
            TaskContext.of("Session", "FindById", sessionId),
        )
    }

    private fun doFindById(sessionId: String): Session? {
        val key = buildKey(sessionId)
        val map = redissonClient.getMap<String, String>(key)

        if (!map.isExists) {
            return null
        }

        val fingerprint = map[FIELD_FINGERPRINT] ?: return null

        return Session(
            sessionId,
            fingerprint,
            map[FIELD_USER_IGN]!!,
            map[FIELD_ACCOUNT_ID]!!,
            map[FIELD_API_KEY]!!,
            deserializeOcids(map[FIELD_MY_OCIDS]),
            map[FIELD_ROLE]!!,
            Instant.parse(map[FIELD_CREATED_AT]!!),
            Instant.parse(map[FIELD_LAST_ACCESSED_AT]),
        )
    }

    override fun refreshTtl(sessionId: String): Boolean {
        return executor.executeOrDefault(
            {
                val key = buildKey(sessionId)
                val map = redissonClient.getMap<String, String>(key)

                if (!map.isExists) {
                    false
                } else {
                    map[FIELD_LAST_ACCESSED_AT] = Instant.now().toString()
                    map.expire(Duration.ofSeconds(sessionTtlSeconds))
                    true
                }
            },
            false,
            TaskContext.of("Session", "RefreshTtl", sessionId),
        )
    }

    override fun deleteById(sessionId: String) {
        executor.executeVoidJava(
            Runnable {
                val key = buildKey(sessionId)
                redissonClient.getMap<String, String>(key).delete()
            },
            TaskContext.of("Session", "Delete", sessionId),
        )
    }

    override fun existsById(sessionId: String): Boolean {
        return executor.executeOrDefault(
            {
                val key = buildKey(sessionId)
                redissonClient.getMap<String, String>(key).isExists
            },
            false,
            TaskContext.of("Session", "Exists", sessionId),
        )
    }

    private fun buildKey(sessionId: String): String = SESSION_KEY_PREFIX + sessionId

    private fun serializeOcids(ocids: Set<String>?): String {
        if (ocids.isNullOrEmpty()) {
            return "[]"
        }
        return executor.executeOrDefault(
            { objectMapper.writeValueAsString(ocids) },
            "[]",
            TaskContext.of("Session", "SerializeOcids", ocids.size.toString()),
        )
    }

    private fun deserializeOcids(json: String?): Set<String> {
        if (json.isNullOrBlank()) {
            return emptySet()
        }
        return executor.executeOrDefault(
            { objectMapper.readValue(json, object : TypeReference<Set<String>>() {}) },
            emptySet(),
            TaskContext.of("Session", "DeserializeOcids", json.take(20)),
        )
    }
}

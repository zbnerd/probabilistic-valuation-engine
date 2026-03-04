package maple.expectation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.domain.auth.RefreshToken
import maple.expectation.domain.repository.RedisRefreshTokenRepository as DomainRedisRefreshTokenRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.jspecify.annotations.Nullable
import org.redisson.api.RBucket
import org.redisson.api.RScript
import org.redisson.api.RSet
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.List

@Repository
open class RedisRefreshTokenRepositoryImpl(
    private val redissonClient: RedissonClient,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    @Value("\${auth.refresh-token.expiration}") private val refreshTokenTtlSeconds: Long,
) : DomainRedisRefreshTokenRepository {

    private companion object {
        const val KEY_PREFIX = "refresh:"
        const val FAMILY_KEY_PREFIX = "refresh:family:"
        const val SESSION_KEY_PREFIX = "refresh:session:"

        val ATOMIC_CHECK_AND_MARK_LUA = """
            local tokenJson = redis.call('GET', KEYS[1])
            if tokenJson == false then
                return nil
            end
            local token = cjson.decode(tokenJson)
            if token.used == true then
                return 'ALREADY_USED'
            end
            token.used = true
            local newJson = cjson.encode(token)
            if ARGV[1] ~= '0' then
                redis.call('PSETEX', KEYS[1], ARGV[1], newJson)
            else
                redis.call('SET', KEYS[1], newJson)
            end
            return newJson
        """.trimIndent()
    }

    override fun save(token: RefreshToken) {
        executor.executeVoidJava(
            Runnable {
                val key = buildTokenKey(token.refreshTokenId())
                val json = serializeToken(token)

                val bucket = redissonClient.getBucket<String>(key)
                bucket.set(json, Duration.ofSeconds(refreshTokenTtlSeconds))

                val familyKey = buildFamilyKey(token.familyId())
                val familySet = redissonClient.getSet<String>(familyKey)
                familySet.add(token.refreshTokenId())
                familySet.expire(Duration.ofSeconds(refreshTokenTtlSeconds))

                val sessionKey = buildSessionKey(token.sessionId())
                val sessionSet = redissonClient.getSet<String>(sessionKey)
                sessionSet.add(token.refreshTokenId())
                sessionSet.expire(Duration.ofSeconds(refreshTokenTtlSeconds))
            },
            TaskContext.of("RefreshToken", "Save", token.refreshTokenId()),
        )
    }

    override fun findById(refreshTokenId: String): RefreshToken? {
        return executor.executeOrDefault(
            { doFindById(refreshTokenId) },
            null,
            TaskContext.of("RefreshToken", "FindById", refreshTokenId),
        )
    }

    private fun doFindById(refreshTokenId: String): RefreshToken? {
        val key = buildTokenKey(refreshTokenId)
        val bucket = redissonClient.getBucket<String>(key)
        val json = bucket.get()
        return json?.let { deserializeToken(it) }
    }

    override fun markAsUsed(refreshTokenId: String) {
        executor.executeVoidJava(
            Runnable {
                val key = buildTokenKey(refreshTokenId)
                val bucket = redissonClient.getBucket<String>(key)
                val json = bucket.get()
                if (json != null) {
                    val token = deserializeToken(json)
                    val usedToken = token.markAsUsed()
                    bucket.set(
                        serializeToken(usedToken),
                        bucket.remainTimeToLive(),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
            },
            TaskContext.of("RefreshToken", "MarkAsUsed", refreshTokenId),
        )
    }

    override fun checkAndMarkAsUsed(refreshTokenId: String): RefreshToken? {
        return executor.executeOrDefault(
            { doCheckAndMarkAsUsed(refreshTokenId) },
            null,
            TaskContext.of("RefreshToken", "CheckAndMark", refreshTokenId),
        )
    }

    private fun doCheckAndMarkAsUsed(refreshTokenId: String): RefreshToken? {
        val key = buildTokenKey(refreshTokenId)
        val bucket = redissonClient.getBucket<String>(key)

        val remainingTtl =
            executor.executeOrDefault(
                { bucket.remainTimeToLive() },
                0L,
                TaskContext.of("RefreshToken", "GetTTL", refreshTokenId),
            )

        if (remainingTtl < 0) {
            return null
        }

        val script = redissonClient.script
        val result =
            script.eval(
                RScript.Mode.READ_WRITE,
                ATOMIC_CHECK_AND_MARK_LUA,
                RScript.ReturnType.VALUE,
                listOf(key),
                (if (remainingTtl > 0) remainingTtl else 0).toString(),
            ) as? String

        return when (result) {
            null -> null
            "ALREADY_USED" -> null
            else -> deserializeToken(result)
        }
    }

    override fun deleteByFamilyId(familyId: String) {
        executor.executeVoidJava(
            Runnable {
                val familyKey = buildFamilyKey(familyId)
                val familySet = redissonClient.getSet<String>(familyKey)
                val tokenIds = familySet.readAll()

                for (tokenId in tokenIds) {
                    val tokenKey = buildTokenKey(tokenId)
                    redissonClient.getBucket<Any>(tokenKey).delete()
                }
                familySet.delete()
            },
            TaskContext.of("RefreshToken", "DeleteByFamily", familyId),
        )
    }

    override fun deleteBySessionId(sessionId: String) {
        executor.executeVoidJava(
            Runnable {
                val sessionKey = buildSessionKey(sessionId)
                val sessionSet = redissonClient.getSet<String>(sessionKey)
                val tokenIds = sessionSet.readAll()

                for (tokenId in tokenIds) {
                    val tokenKey = buildTokenKey(tokenId)
                    val bucket = redissonClient.getBucket<String>(tokenKey)
                    val json = bucket.get()

                    if (json != null) {
                        val token = deserializeToken(json)
                        val familyKey = buildFamilyKey(token.familyId())
                        redissonClient.getSet<String>(familyKey).remove(tokenId)
                    }
                    bucket.delete()
                }
                sessionSet.delete()
            },
            TaskContext.of("RefreshToken", "DeleteBySession", sessionId),
        )
    }

    override fun deleteById(refreshTokenId: String) {
        executor.executeVoidJava(
            Runnable {
                val tokenKey = buildTokenKey(refreshTokenId)
                redissonClient.getBucket<Any>(tokenKey).delete()
            },
            TaskContext.of("RefreshToken", "DeleteById", refreshTokenId),
        )
    }

    private fun buildTokenKey(refreshTokenId: String): String = KEY_PREFIX + refreshTokenId

    private fun buildFamilyKey(familyId: String): String = FAMILY_KEY_PREFIX + familyId

    private fun buildSessionKey(sessionId: String): String = SESSION_KEY_PREFIX + sessionId

    private fun serializeToken(token: RefreshToken): String {
        return executor.execute(
            { objectMapper.writeValueAsString(token) },
            TaskContext.of("RefreshToken", "Serialize", token.refreshTokenId()),
        )
    }

    private fun deserializeToken(json: String): RefreshToken {
        return executor.execute(
            { objectMapper.readValue(json, RefreshToken::class.java) },
            TaskContext.of("RefreshToken", "Deserialize", json.take(30)),
        )
    }
}

package maple.expectation.infrastructure.config

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.AtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.queue.like.strategy.LuaScriptAtomicFetchStrategy
import maple.expectation.infrastructure.queue.like.strategy.RenameAtomicFetchStrategy
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * LikeSync 전략 선택 설정 (Strategy Pattern)
 *
 * <p>금융수준 안전 설계:
 *
 * <ul>
 *   <li><b>lua (기본)</b>: Lua Script 기반 원자적 연산 (권장)
 *   <li><b>rename (폴백)</b>: RENAME 기반 (Lua 미지원 환경)
 * </ul>
 *
 * <p>설정:
 *
 * <pre>{@code
 * like:
 *   sync:
 *     strategy: lua  # lua | rename
 * }</pre>
 *
 * @since 2.0.0
 */
@Configuration
class LikeSyncConfig(
    @Value("\${like.sync.strategy:lua}") private val strategyType: String,
    @Value("\${like.sync.temp-key-ttl-seconds:3600}") private val tempKeyTtlSeconds: Int,
) {

    companion object {
        private const val STRATEGY_LUA = "lua"
        private const val STRATEGY_RENAME = "rename"
    }

    private val log = LoggerFactory.getLogger(LikeSyncConfig::class.java)

    /**
     * AtomicFetchStrategy Bean 등록
     *
     * <p>설정에 따라 Lua Script 또는 Rename 전략 선택
     */
    @Bean
    fun atomicFetchStrategy(
        redissonClient: RedissonClient,
        redisTemplate: StringRedisTemplate,
        executor: LogicExecutor,
        meterRegistry: MeterRegistry,
    ): AtomicFetchStrategy = when (strategyType.lowercase()) {
        STRATEGY_RENAME -> {
            log.info("AtomicFetchStrategy initialized: RENAME (fallback), TTL={}s", tempKeyTtlSeconds)
            RenameAtomicFetchStrategy(redisTemplate, executor, meterRegistry, tempKeyTtlSeconds)
        }
        else -> {
            log.info("AtomicFetchStrategy initialized: LUA_SCRIPT (primary), TTL={}s", tempKeyTtlSeconds)
            LuaScriptAtomicFetchStrategy(redissonClient, executor, meterRegistry, tempKeyTtlSeconds)
        }
    }
}

package maple.expectation.infrastructure.cache.like

import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeBufferStrategy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-Memory 좋아요 카운터 버퍼 (Caffeine 기반)
 *
 * V5 Stateless 전환:
 * - 이 구현체는 단일 인스턴스 환경용
 * - Scale-out 환경에서는 app.buffer.redis.enabled=true 설정으로 Redis 기반 구현체 사용
 *
 * 제약사항:
 * - 인스턴스별 독립 버퍼 → Scale-out 시 데이터 분산
 * - 인스턴스 장애 시 버퍼 데이터 유실
 *
 * @see maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage Redis 구현
 */
@ConditionalOnProperty(name = ["app.buffer.redis.enabled"], havingValue = "false", matchIfMissing = true)
@Component
class InMemoryLikeBufferStorage(
    registry: MeterRegistry,
    @Value("\${like.buffer.local.max-size:10000}") maxSize: Int
) : LikeBufferStrategy {

    private val likeCache = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(maxSize.toLong())
        .build<String, AtomicLong>()

    init {
        Gauge.builder("like.buffer.local_pending", this) { storage ->
            storage.likeCache.asMap().values.sumOf { it.get().toDouble() }
        }
        .description("현재 인스턴스의 미반영 좋아요 총합")
        .register(registry)
    }

    override fun increment(userIgn: String, delta: Long): Long {
        return getCounter(userIgn).addAndGet(delta)
    }

    override fun get(userIgn: String): Long {
        return likeCache.getIfPresent(userIgn)?.get() ?: 0L
    }

    override fun getAllCounters(): Map<String, Long> {
        return likeCache.asMap().mapValues { it.value.get().toLong() }
    }

    override fun fetchAndClear(limit: Int): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        var count = 0

        for ((key, counter) in likeCache.asMap()) {
            if (count >= limit) break
            val value = counter.getAndSet(0)
            if (value != 0L) {
                result[key] = value
                count++
            }
        }

        return result
    }

    override fun getBufferSize(): Int = likeCache.estimatedSize().toInt()

    override fun getType(): LikeBufferStrategy.StrategyType = LikeBufferStrategy.StrategyType.IN_MEMORY

    /**
     * 카운터 조회 (없으면 생성)
     */
    fun getCounter(userIgn: String): AtomicLong =
        likeCache.get(userIgn) { AtomicLong(0) }

    /**
     * 내부 캐시 접근 (테스트 초기화용)
     */
    fun getCache() = likeCache

    companion object {
        private val log = LoggerFactory.getLogger(InMemoryLikeBufferStorage::class.java)
    }
}

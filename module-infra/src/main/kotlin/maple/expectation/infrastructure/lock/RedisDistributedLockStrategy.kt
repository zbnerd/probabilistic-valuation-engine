package maple.expectation.infrastructure.lock

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Redis 분산 락 전략 (Redisson 기반)
 *
 * <p>AbstractLockStrategy를 상속하여 85% 이상의 보일러플레이트 코드를 제거했습니다.
 */
@Component
@Qualifier("redisDistributedLockStrategy")
class RedisDistributedLockStrategy(
    private val redissonClient: RedissonClient,
    executor: LogicExecutor,
    private val lockMetrics: LockMetrics
) : AbstractLockStrategy(executor) {

    /**
     * Redisson Watchdog 모드로 락 획득 (CLAUDE.md 섹션 17 준수)
     */
    @Throws(Throwable::class)
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        val lock: RLock = redissonClient.getLock(lockKey)
        val startTime = System.currentTimeMillis()

        // ✅ Watchdog 모드: leaseTime 생략 → 30초마다 자동 갱신
        val acquired = lock.tryLock(waitTime, TimeUnit.SECONDS)

        // [Issue #310] 락 대기 시간 기록
        if (acquired) {
            val waitTimeMs = System.currentTimeMillis() - startTime
            lockMetrics.recordWaitTime(waitTimeMs, "redis")
        }

        return acquired
    }

    override fun unlockInternal(lockKey: String) {
        val lock: RLock = redissonClient.getLock(lockKey)
        if (lock.isHeldByCurrentThread) {
            lock.unlock()
            // [Issue #310] 락 해제 기록
            lockMetrics.recordLockReleased("redis")
        }
    }

    override fun shouldUnlock(lockKey: String): Boolean {
        return redissonClient.getLock(lockKey).isHeldByCurrentThread
    }

    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        val lockKey = buildLockKey(key)

        return executor.executeOrDefault(
            { this.attemptImmediateLock(lockKey, leaseTime) },
            false,
            TaskContext.of("Lock", "RedisTryImmediate", key) // ✅ TaskContext 적용
        )
    }

    @Throws(Throwable::class)
    private fun attemptImmediateLock(lockKey: String, leaseTime: Long): Boolean {
        return tryLock(lockKey, 0, leaseTime)
    }

    override fun onLockAcquired(lockKey: String) {
        // [Issue #310] 락 획득 성공 기록
        lockMetrics.recordLockAcquired("redis")
        log.debug("🔓 [Lock] '{}' 획득 성공", lockKey)
    }

    override fun onLockFailed(lockKey: String) {
        // [Issue #310] 락 획득 실패 기록
        lockMetrics.recordFailure("redis")
        log.warn("⏭️ [Lock] '{}' 획득 실패", lockKey)
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(RedisDistributedLockStrategy::class.java)
    }
}

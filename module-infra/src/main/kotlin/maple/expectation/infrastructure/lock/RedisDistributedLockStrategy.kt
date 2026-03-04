package maple.expectation.infrastructure.lock

import maple.expectation.core.port.out.redis.RedisOperationPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis 분산 락 전략 (RedisOperationPort 기반)
 *
 * <p>AbstractLockStrategy를 상속하여 85% 이상의 보일러플레이트 코드를 제거했습니다.
 *
 * <h4>ADR-012: DIP 준수</h4>
 * <p>RedissonClient 대신 RedisOperationPort에 의존하여 DIP 준수.
 */
@Component
@Qualifier("redisDistributedLockStrategy")
class RedisDistributedLockStrategy(
    private val redisOperationPort: RedisOperationPort,
    executor: LogicExecutor,
    private val lockMetrics: LockMetrics
) : AbstractLockStrategy(executor) {

    /**
     * Redisson Watchdog 모드로 락 획득 (CLAUDE.md 섹션 17 준수)
     */
    @Throws(Throwable::class)
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        val startTime = System.currentTimeMillis()

        // ✅ Watchdog 모드: leaseTime 생략 → 30초마다 자동 갱신
        val acquired = redisOperationPort.tryLockWithWatchdog(lockKey, Duration.ofSeconds(waitTime))

        // [Issue #310] 락 대기 시간 기록
        if (acquired) {
            val waitTimeMs = System.currentTimeMillis() - startTime
            lockMetrics.recordWaitTime(waitTimeMs, "redis")
        }

        return acquired
    }

    override fun unlockInternal(lockKey: String) {
        if (redisOperationPort.isHeldByCurrentThread(lockKey)) {
            redisOperationPort.unlock(lockKey)
            // [Issue #310] 락 해제 기록
            lockMetrics.recordLockReleased("redis")
        }
    }

    override fun shouldUnlock(lockKey: String): Boolean {
        return redisOperationPort.isHeldByCurrentThread(lockKey)
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

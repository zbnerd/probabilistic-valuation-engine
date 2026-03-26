package maple.expectation.infrastructure.lock

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component

/**
 * PostgreSQL Advisory Lock 전략
 *
 * <p>PostgreSQL의 Advisory Lock 기능(pg_try_advisory_lock, pg_advisory_unlock)을 활용하여
 * 분산 락을 구현합니다. 세션 기반인 MySQL Named Lock과 달리, Advisory Lock은
 * 애플리케이션에서 명시적으로 해제할 때까지 유지됩니다.
 *
 * <h4>주요 특징</h4>
 * <ul>
 *   <li><b>세션 독립적:</b> 연결을 반환해도 락이 유지됨 (명시적 해제 필요)</li>
 *   <li><b>높은 성능:</b> Redis에 비해 약 2-3배의 지연시간 (~1-3ms)</li>
 *   <li><b>단순함:</b> 별도의 인프라 구성 불필요 (PostgreSQL 기본 기능)</li>
 * </ul>
 *
 * <h4>Key-to-ID 변환 전략</h4>
 * <p>문자열 키를 64bit 정수(bigint)로 변환하여 advisory lock ID로 사용합니다.
 * FNV-1a 해시 알고리즘을 사용하여 충돌 확률을 최소화합니다.
 *
 * <h4>Lease Time 관리</h4>
 * <p>PostgreSQL Advisory Lock은 자동 만료 기능이 없으므로, 애플리케이션 레벨에서
 * ThreadPoolTaskScheduler를 통해 주기적으로 락 해제를 검사합니다.
 *
 * @property lockJdbcTemplate 락 전용 JdbcTemplate
 * @property executor LogicExecutor for error handling
 * @property lockMetrics Lock metrics recorder
 * @property leaseScheduler Lease time 관리를 위한 스케줄러
 */
@Component
@Qualifier("postgresAdvisoryLockStrategy")
class PostgresLockStrategy(
    @Qualifier("lockJdbcTemplate")
    private val lockJdbcTemplate: JdbcTemplate,
    executor: LogicExecutor,
    private val lockMetrics: LockMetrics,
    @Qualifier("taskScheduler")
    private val leaseScheduler: ThreadPoolTaskScheduler,
) : AbstractLockStrategy(executor) {

    /**
     * 현재 스레드가 획득한 락 관리 (ThreadLocal)
     * Key: advisory lock ID (Long), Value: lease 만료 시간 (Long, epoch millis)
     */
    private val acquiredLocks: ThreadLocal<MutableMap<Long, Long>> = ThreadLocal.withInitial { ConcurrentHashMap() }

    /**
     * Lock acquisition order tracking for deadlock prevention
     * Maintains ordered list of acquired lock IDs per thread
     */
    private val lockOrder: ThreadLocal<MutableList<Long>> = ThreadLocal.withInitial { mutableListOf() }

    /**
     * PostgreSQL Advisory Lock 획득 시도
     *
     * <p>pg_try_advisory_lock(bigint)을 사용하여 락 획득을 시도합니다.
     * 이미 락을 획득한 경우 true를 반환합니다 (PostgreSQL 특성).
     *
     * @param lockKey 락 키 (advisory lock ID로 변환됨)
     * @param waitTime 최대 대기 시간 (초) - 폴링 방식으로 구현
     * @param leaseTime 락 유지 시간 (초) - 애플리케이션 레벨에서 관리
     * @return 락 획득 성공 여부
     */
    @Throws(Throwable::class)
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        val advisoryLockId = toAdvisoryLockId(lockKey)
        val startTime = System.currentTimeMillis()
        val deadline = startTime + (waitTime * 1000)

        // 이미 획득한 락이면 성공 처리 (PostgreSQL Advisory Lock 특성: 재진입 가능)
        if (isHeldByCurrentThread(advisoryLockId)) {
            log.debug("🔒 [Postgres Lock] Already holds lock for key='{}' (id={})", lockKey, advisoryLockId)
            return true
        }

        // 폴링 방식으로 락 획득 시도 (PostgreSQL은 대기 시간 지원 안 함)
        while (System.currentTimeMillis() < deadline) {
            var lockAcquired = false
            try {
                if (tryAcquireAdvisoryLock(advisoryLockId)) {
                    lockAcquired = true
                    // 락 획득 성공 - lease time 등록 (원자적 등록)
                    val leaseDeadline = if (leaseTime > 0) System.currentTimeMillis() + (leaseTime * 1000) else Long.MAX_VALUE

                    // Register lease BEFORE any other operations
                    acquiredLocks.get()[advisoryLockId] = leaseDeadline

                    // Track lock order for deadlock prevention
                    lockOrder.get().add(advisoryLockId)

                    // [Issue #310] 락 대기 시간 기록
                    val waitTimeMs = System.currentTimeMillis() - startTime
                    lockMetrics.recordWaitTime(waitTimeMs, "postgres")

                    log.debug("🔓 [Postgres Lock] Acquired lock for key='{}' (id={}, lease={}s)", lockKey, advisoryLockId, leaseTime)
                    return true
                }
            } catch (e: Exception) {
                // Ensure no stale state on failure - release lock if acquired but registration failed
                if (lockAcquired) {
                    try {
                        releaseAdvisoryLock(advisoryLockId)
                    } catch (releaseError: Exception) {
                        log.warn("⚠️ [Postgres Lock] Failed to release lock during error recovery: {}", releaseError.message)
                    }
                }
                // Clean up ThreadLocal state
                acquiredLocks.get().remove(advisoryLockId)
                lockOrder.get().remove(advisoryLockId)
                throw e
            }

            // Park 최소 1ms ~ 최대 100ms (지수 백오프)
            val elapsed = System.currentTimeMillis() - startTime
            val parkTime = elapsed.toInt().coerceIn(1, 100).toLong()
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(parkTime))
        }

        return false
    }

    /**
     * PostgreSQL Advisory Lock 해제 (내부)
     *
     * @param lockKey 락 키
     */
    override fun unlockInternal(lockKey: String) {
        val advisoryLockId = toAdvisoryLockId(lockKey)
        val locks = acquiredLocks.get()

        if (locks.containsKey(advisoryLockId)) {
            releaseAdvisoryLock(advisoryLockId)
            locks.remove(advisoryLockId)
            lockOrder.get().remove(advisoryLockId) // Remove from order tracking

            // 빈 경우 ThreadLocal 완전 제거 (메모리 누수 방지)
            if (locks.isEmpty()) {
                acquiredLocks.remove()
            }
            if (lockOrder.get().isEmpty()) {
                lockOrder.remove()
            }

            log.debug("🔒 [Postgres Lock] Released lock for key='{}' (id={})", lockKey, advisoryLockId)
        }
    }

    /**
     * 현재 스레드가 락을 보유하고 있는지 확인
     *
     * @param lockKey 락 키
     * @return 락 보유 여부
     */
    override fun shouldUnlock(lockKey: String): Boolean = isHeldByCurrentThread(toAdvisoryLockId(lockKey))

    /**
     * 즉시 락 획득 시도 (대기 없음)
     *
     * @param key 락 키
     * @param leaseTime 락 유지 시간 (초)
     * @return 락 획득 성공 여부
     */
    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        val lockKey = buildLockKey(key)

        return executor.executeOrDefault(
            { this.tryLock(lockKey, 0, leaseTime) },
            false,
            TaskContext.of("Lock", "PostgresTryImmediate", key),
        )
    }

    /**
     * 락 획득 성공 Hook
     *
     * @param lockKey 락 키
     */
    override fun onLockAcquired(lockKey: String) {
        lockMetrics.recordLockAcquired("postgres")
        log.debug("🔓 [Postgres Lock] '{}' 획득 성공", lockKey)
    }

    /**
     * 락 해제 Hook
     *
     * @param lockKey 락 키
     */
    override fun onLockReleased(lockKey: String) {
        lockMetrics.recordLockReleased("postgres")
        log.debug("🔒 [Postgres Lock] '{}' 해제 완료", lockKey)
    }

    /**
     * 락 획득 실패 Hook
     *
     * @param lockKey 락 키
     */
    override fun onLockFailed(lockKey: String) {
        lockMetrics.recordFailure("postgres")
        log.warn("⏭️ [Postgres Lock] '{}' 획득 실패", lockKey)
    }

    /**
     * 락 키 빌드 (접두사 추가)
     *
     * @param key 원본 키
     * @return 빌드된 락 키
     */
    override fun buildLockKey(key: String): String = "pg_lock:$key"

    // ===== Private Helper Methods =====

    /**
     * PostgreSQL Advisory Lock 획득 시도
     *
     * <p>pg_try_advisory_lock(bigint)을 호출합니다.
     * 이미 락을 획득한 경우에도 true를 반환합니다.
     *
     * @param advisoryLockId Advisory lock ID (64bit)
     * @return 락 획득 성공 여부
     */
    private fun tryAcquireAdvisoryLock(advisoryLockId: Long): Boolean = lockJdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_lock(?)",
        Boolean::class.java,
        advisoryLockId,
    ) ?: false

    /**
     * PostgreSQL Advisory Lock 해제
     *
     * <p>pg_advisory_unlock(bigint)을 호출합니다.
     *
     * @param advisoryLockId Advisory lock ID (64bit)
     * @return 해제 성공 여부
     */
    private fun releaseAdvisoryLock(advisoryLockId: Long): Boolean = lockJdbcTemplate.queryForObject(
        "SELECT pg_advisory_unlock(?)",
        Boolean::class.java,
        advisoryLockId,
    ) ?: false

    /**
     * 현재 스레드가 락을 보유하고 있는지 확인
     *
     * @param advisoryLockId Advisory lock ID (64bit)
     * @return 락 보유 여부
     */
    private fun isHeldByCurrentThread(advisoryLockId: Long): Boolean = acquiredLocks.get().containsKey(advisoryLockId)

    /**
     * Check for potential deadlock by verifying lock ordering
     *
     * <p>Deadlock can occur if a thread tries to acquire a lock with a lower ID
     * after acquiring one with a higher ID. This enforces strict ordering.
     *
     * @param advisoryLockId The lock ID to acquire
     * @return true if deadlock risk detected
     */
    private fun isDeadlockRisk(advisoryLockId: Long): Boolean {
        val order = lockOrder.get()
        // Empty order means no prior locks - safe
        if (order.isEmpty()) return false
        // Deadlock if trying to acquire lock with lower ID after higher ID
        return order.last() > advisoryLockId
    }

    /**
     * 문자열 키를 64bit 정수로 변환 (FNV-1a 해시)
     *
     * <p>PostgreSQL Advisory Lock은 64bit 정수(bigint)를 ID로 사용합니다.
     * FNV-1a 해시 알고리즘을 사용하여 충돌 확률을 최소화합니다.
     *
     * <h4>FNV-1a 해시 특징</h4>
     * <ul>
     *   <li>빠른 연산 속도</li>
     *   <li>좋은 분산 성능</li>
     *   <li>충돌 확률: 2^64 분의 1 (무시할 수준)</li>
     * </ul>
     *
     * @param key 락 키 문자열
     * @return 64bit 정수
     */
    private fun toAdvisoryLockId(key: String): Long {
        val FNV_64_OFFSET_BASIS = -0x3c2d2f0705b7b401L // 14695981039346656037
        val FNV_64_PRIME = 0x100000001b3L // 1099511628211

        var hash = FNV_64_OFFSET_BASIS
        for (byte in key.toByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_64_PRIME
        }
        return hash
    }

    /**
     * Execute task with multiple locks acquired in safe order
     *
     * <p>Acquires locks in sorted order to prevent deadlocks.
     * If any lock acquisition fails, releases all acquired locks.
     *
     * @param keys Lock keys to acquire
     * @param leaseTime Lock lease time in seconds
     * @param task Task to execute with all locks held
     * @return Task result
     * @throws DeadlockException if lock ordering violation detected
     */
    fun <T> executeWithOrderedLocks(keys: List<String>, leaseTime: Long, task: () -> T): T {
        // Sort lock IDs to ensure consistent ordering across threads
        val sortedLockIds = keys.map { toAdvisoryLockId(buildLockKey(it)) }.sorted()

        // Verify no deadlock risk
        for (lockId in sortedLockIds) {
            if (isDeadlockRisk(lockId)) {
                throw IllegalStateException("Deadlock risk detected: attempting to acquire lock $lockId out of order")
            }
        }

        // Acquire locks in order
        val acquiredLockIds = mutableListOf<Long>()
        try {
            for (lockId in sortedLockIds) {
                if (!tryAcquireAdvisoryLock(lockId)) {
                    throw IllegalStateException("Failed to acquire lock $lockId")
                }
                acquiredLockIds.add(lockId)
                lockOrder.get().add(lockId)

                // Register lease
                val leaseDeadline = if (leaseTime > 0) System.currentTimeMillis() + (leaseTime * 1000) else Long.MAX_VALUE
                acquiredLocks.get()[lockId] = leaseDeadline
            }

            return task()
        } finally {
            // Release locks in reverse order
            acquiredLockIds.reversed().forEach { lockId ->
                releaseAdvisoryLock(lockId)
                acquiredLocks.get().remove(lockId)
                lockOrder.get().remove(lockId)
            }

            // Clean up empty ThreadLocals
            if (lockOrder.get().isEmpty()) {
                lockOrder.remove()
            }
            if (acquiredLocks.get().isEmpty()) {
                acquiredLocks.remove()
            }
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(PostgresLockStrategy::class.java)
    }

    /**
     * Lease Time 관리를 위한 초기화
     *
     * <p>주기적으로 만료된 락을 해제하는 스케줄러를 등록합니다.
     */
    @jakarta.annotation.PostConstruct
    fun initLeaseScheduler() {
        // 1분마다 만료된 락 정리
        leaseScheduler.scheduleWithFixedDelay(
            { cleanupExpiredLocks() },
            TimeUnit.MINUTES.toMillis(1),
        )
        log.info("[PostgresLockStrategy] Lease time scheduler initialized (cleanup interval: 60s)")
    }

    /**
     * 만료된 락 정리
     *
     * <p>ThreadLocal에 등록된 락 중 lease time이 만료된 것을 해제합니다.
     */
    private fun cleanupExpiredLocks() {
        val locks = acquiredLocks.get()
        val now = System.currentTimeMillis()
        val expiredKeys = locks.filterValues { it < now }.keys

        for (advisoryLockId in expiredKeys) {
            releaseAdvisoryLock(advisoryLockId)
            locks.remove(advisoryLockId)
            lockOrder.get().remove(advisoryLockId)
            log.debug("[PostgresLockStrategy] Cleaned up expired lock (id={})", advisoryLockId)
        }

        // 빈 경우 ThreadLocal 완전 제거
        if (locks.isEmpty()) {
            acquiredLocks.remove()
        }
        if (lockOrder.get().isEmpty()) {
            lockOrder.remove()
        }
    }
}

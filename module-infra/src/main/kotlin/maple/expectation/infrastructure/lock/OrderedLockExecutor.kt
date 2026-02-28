package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.stereotype.Component
import java.util.ArrayList
import java.util.List
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 순서 보장 다중 락 실행기 (Issue #221: N02-Lock Ordering Deadlock)
 */
@Component
class OrderedLockExecutor(
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor
) {

    /**
     * 순서 보장 다중 락 실행 (반복 패턴)
     */
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        val context = TaskContext.of("OrderedLock", "Execute", java.lang.String.join(",", keys))

        return executor.execute(
            { executeWithOrderedLocksInternal(keys, totalTimeout, timeUnit, leaseTime, task) },
            context
        )
    }

    /**
     * 내부 구현: 반복 패턴 또는 중첩 콜백으로 락 획득 및 실행
     */
    @Throws(Throwable::class)
    private fun <T> executeWithOrderedLocksInternal(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        // 1. 정렬하여 Circular Wait 조건 제거
        val sortedKeys: java.util.List<String> = keys.sorted() as java.util.List<String>

        log.debug("[OrderedLock] Acquiring {} locks in order: {}", sortedKeys.size, sortedKeys)

        // 2. PR #236: MySQL Named Lock 감지 → 중첩 콜백 전략 사용
        if (requiresNestedStrategy()) {
            log.debug("[OrderedLock] Using nested callback strategy (MySQL Named Lock detected)")
            return executeWithNestedLocks(
                sortedKeys,
                0,
                timeUnit.toMillis(totalTimeout),
                leaseTime,
                task
            )
        }

        // 3. Redisson: 기존 반복 패턴 사용
        return executeWithIterativeStrategy(sortedKeys, totalTimeout, timeUnit, leaseTime, task)
    }

    /** Redisson용 반복 패턴 전략 */
    @Throws(Throwable::class)
    private fun <T> executeWithIterativeStrategy(
        sortedKeys: java.util.List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        // [P0-RED-01] deadline 계산 (나노초 정밀도)
        val deadlineNanos = System.nanoTime() + timeUnit.toNanos(totalTimeout)

        // 획득한 락 추적 (LIFO 해제용)
        @Suppress("USELESS_CAST")
        val acquiredLocks: java.util.List<String> = java.util.ArrayList<String>() as java.util.List<String>

        val context = TaskContext.of("OrderedLock", "IterativeStrategy", java.lang.String.join(",", sortedKeys))

        return executor.executeWithFinally(
            { acquireLocksAndExecute(sortedKeys, deadlineNanos, leaseTime, task, acquiredLocks) },
            { releaseLocksInReverseOrder(acquiredLocks) },
            context
        )
    }

    /** 락 순차 획득 후 작업 실행 */
    @Throws(Throwable::class)
    private fun <T> acquireLocksAndExecute(
        sortedKeys: java.util.List<String>,
        deadlineNanos: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
        acquiredLocks: java.util.List<String>
    ): T {
        for (i in sortedKeys.indices) {
            val currentKey = sortedKeys[i]

            // [P0-RED-01] 남은 시간 계산
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw DistributedLockException(
                    String.format("전체 락 타임아웃 초과: %d/%d 락 획득 중 [key=%s]", i, sortedKeys.size, currentKey)
                )
            }

            // 남은 시간을 waitTime으로 변환 (최소 1초, 최대 10초)
            val remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos)
            val waitTimeSec = Math.max(1, Math.min(remainingSeconds, 10))

            log.debug(
                "[OrderedLock] Acquiring lock {}/{}: {} (remaining: {}ms)",
                i + 1,
                sortedKeys.size,
                currentKey,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos)
            )

            // 락 획득 시도
            val acquired = tryAcquireLock(currentKey, waitTimeSec, leaseTime)
            if (!acquired) {
                throw DistributedLockException(
                    String.format("락 획득 실패: %s (waited %ds)", currentKey, waitTimeSec)
                )
            }

            acquiredLocks.add(currentKey)
        }

        log.info("[OrderedLock] All {} locks acquired, executing task", sortedKeys.size)

        // 작업 실행
        return task.get()
    }

    /**
     * 락 획득 시도
     */
    private fun tryAcquireLock(key: String, waitTimeSec: Long, leaseTime: Long): Boolean {
        val context = TaskContext.of("OrderedLock", "TryAcquire", key)

        return executor.executeOrDefault(
            { lockStrategy.tryLockImmediately(key, leaseTime) },
            false,
    // UnsupportedOperationException 또는 기타 예외 시 false 반환 → 중첩 전략으로 전환
            context
        )
    }

    /**
     * PR #236 Fix: MySQL Named Lock용 중첩 콜백 전략
     */
    @Throws(Throwable::class)
    private fun <T> executeWithNestedLocks(
        sortedKeys: java.util.List<String>,
        currentIndex: Int,
        remainingTimeoutMs: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        // P1-YELLOW-01: 스택 깊이 제한
        if (currentIndex >= MAX_NESTED_DEPTH) {
            throw DistributedLockException(
                String.format("중첩 락 깊이 초과: 최대 %d개까지 지원 (요청: %d개)", MAX_NESTED_DEPTH, sortedKeys.size)
            )
        }

        // Base case: 모든 락 획득 완료 → 작업 실행
        if (currentIndex >= sortedKeys.size) {
            log.info("[OrderedLock/Nested] All {} locks acquired, executing task", sortedKeys.size)
            return task.get()
        }

        val currentKey = sortedKeys[currentIndex]
        val waitTimeSec = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingTimeoutMs))

        log.debug(
            "[OrderedLock/Nested] Acquiring lock {}/{}: {} (remaining: {}ms)",
            currentIndex + 1,
            sortedKeys.size,
            currentKey,
            remainingTimeoutMs
        )

        // 중첩 콜백: 현재 락 안에서 다음 락 획득
        return lockStrategy.executeWithLock(
            currentKey,
            waitTimeSec,
            leaseTime
        ) {
            executeWithNestedLocks(
                sortedKeys,
                currentIndex + 1,
                remainingTimeoutMs - TimeUnit.SECONDS.toMillis(waitTimeSec),
                leaseTime,
                task
            )
        }
    }

    /**
     * P1-BLUE-01 Fix: MySQL Named Lock 지원 여부 확인 (결과 캐싱)
     */
    private fun requiresNestedStrategy(): Boolean {
        val cached = nestedStrategyRequired.get()
        if (cached != null) {
            return cached
        }

        // Lock-Free CAS: 최초 한 번만 probe 실행
        val detected = detectNestedStrategyRequired()
        if (nestedStrategyRequired.compareAndSet(null, detected)) {
            log.info("[OrderedLock] Strategy detection: nestedRequired={}", detected)
            return detected
        }

        // CAS 실패 시 다른 스레드가 이미 설정한 값 반환
        return nestedStrategyRequired.get()!!
    }

    /**
     * 전략 감지
     */
    private fun detectNestedStrategyRequired(): Boolean {
        val context = TaskContext.of("OrderedLock", "StrategyProbe", "__probe__:strategy__")

        // executeOrDefault: 예외 발생 시 true 반환 → MySQL 중첩 전략
        return executor.executeOrDefault(
            {
                lockStrategy.tryLockImmediately("__probe__:strategy__", 1)
                unlockSafely("__probe__:strategy__")
                false // Redisson: 일반 전략 사용
            },
            true,
    // UnsupportedOperationException 또는 기타 예외 → MySQL 중첩 전략
            context
        )
    }

    /**
     * [Green Agent] LIFO 순서로 락 해제
     */
    private fun releaseLocksInReverseOrder(acquiredLocks: List<String>) {
        for (i in acquiredLocks.size - 1 downTo 0) {
            val lockKey = acquiredLocks[i]
            unlockSafely(lockKey)
        }
    }

    /**
     * 안전한 락 해제
     */
    private fun unlockSafely(lockKey: String) {
        val context = TaskContext.of("OrderedLock", "Unlock", lockKey)

        executor.executeVoidJava(
            {
                lockStrategy.unlock(lockKey)
                log.debug("[OrderedLock] Released lock: {}", lockKey)
            },
            context
        )
    }

    /** 편의 메서드: 초 단위 타임아웃 */
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeoutSeconds: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        return executeWithOrderedLocks(keys, totalTimeoutSeconds, TimeUnit.SECONDS, leaseTime, task)
    }

    companion object {
        private const val MAX_NESTED_DEPTH = 10 // P1-YELLOW-01: 스택 깊이 제한
        private val log = org.slf4j.LoggerFactory.getLogger(OrderedLockExecutor::class.java)
    }

    // P1-BLUE-01: 전략 캐싱 (Lock-Free 초기화)
    private val nestedStrategyRequired = AtomicReference<Boolean?>()
}

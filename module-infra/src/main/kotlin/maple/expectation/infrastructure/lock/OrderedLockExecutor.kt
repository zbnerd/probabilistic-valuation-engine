package maple.expectation.infrastructure.lock

import java.util.ArrayList
import java.util.List
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.stereotype.Component

/**
 * 순서 보장 다중 락 실행기 (Issue #221: N02-Lock Ordering Deadlock)
 */
@Component
class OrderedLockExecutor(
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
) {

    /**
     * [Async preferred] 순서 보장 다중 락 실행 — CompletableFuture 반환.
     *
     * Caller thread는 절대 block되지 않는다. 락 획득과 supplier 실행이 모두
     * CompletableFuture 체인에서 비동기로 진행된다.
     */
    fun <T> executeWithOrderedLocksAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val context = TaskContext.of("OrderedLock", "ExecuteAsync", java.lang.String.join(",", keys))
        return executeWithOrderedLocksInternalAsync(keys, totalTimeout, timeUnit, leaseTime, supplier, context)
    }

    /**
     * 순서 보장 다중 락 실행 (반복 패턴)
     *
     * @deprecated Use [executeWithOrderedLocksAsync] — sync version blocks caller on `task.get()`.
     *             Kept for module-app legacy migration; new code must use the *Async variant.
     */
    @Deprecated("Use executeWithOrderedLocksAsync — sync blocks caller thread on task.get()")
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
    ): T {
        val context = TaskContext.of("OrderedLock", "Execute", java.lang.String.join(",", keys))

        return executor.execute(
            { executeWithOrderedLocksInternal(keys, totalTimeout, timeUnit, leaseTime, task) },
            context,
        )
    }

    /**
     * 내부 구현: 반복 패턴 또는 중첩 콜백으로 락 획득 및 실행
     *
     * @deprecated Use [executeWithOrderedLocksInternalAsync].
     */
    @Deprecated("Use executeWithOrderedLocksInternalAsync")
    @Throws(Throwable::class)
    private fun <T> executeWithOrderedLocksInternal(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
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
                task,
            )
        }

        // 3. Redisson: 기존 반복 패턴 사용
        return executeWithIterativeStrategy(sortedKeys, totalTimeout, timeUnit, leaseTime, task)
    }

    /** Redisson용 반복 패턴 전략 */
    @Throws(Throwable::class)
    @Deprecated("Use executeWithIterativeStrategyAsync")
    private fun <T> executeWithIterativeStrategy(
        sortedKeys: java.util.List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
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
            context,
        )
    }

    /** 락 순차 획득 후 작업 실행 */
    @Throws(Throwable::class)
    @Deprecated("Use acquireLocksAndExecuteAsync — sync version calls task.get() at line 143")
    private fun <T> acquireLocksAndExecute(
        sortedKeys: java.util.List<String>,
        deadlineNanos: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
        acquiredLocks: java.util.List<String>,
    ): T {
        for (i in sortedKeys.indices) {
            val currentKey = sortedKeys[i]

            // [P0-RED-01] 남은 시간 계산
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw DistributedLockException(
                    String.format("전체 락 타임아웃 초과: %d/%d 락 획득 중 [key=%s]", i, sortedKeys.size, currentKey),
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
                TimeUnit.NANOSECONDS.toMillis(remainingNanos),
            )

            // 락 획득 시도
            val acquired = tryAcquireLock(currentKey, waitTimeSec, leaseTime)
            if (!acquired) {
                throw DistributedLockException(
                    String.format("락 획득 실패: %s (waited %ds)", currentKey, waitTimeSec),
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
            context,
        )
    }

    /**
     * PR #236 Fix: MySQL Named Lock용 중첩 콜백 전략
     */
    @Throws(Throwable::class)
    @Deprecated("Use executeWithNestedLocksAsync — sync version calls task.get() at line 181")
    private fun <T> executeWithNestedLocks(
        sortedKeys: java.util.List<String>,
        currentIndex: Int,
        remainingTimeoutMs: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
    ): T {
        // P1-YELLOW-01: 스택 깊이 제한
        if (currentIndex >= MAX_NESTED_DEPTH) {
            throw DistributedLockException(
                String.format("중첩 락 깊이 초과: 최대 %d개까지 지원 (요청: %d개)", MAX_NESTED_DEPTH, sortedKeys.size),
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
            remainingTimeoutMs,
        )

        // 중첩 콜백: 현재 락 안에서 다음 락 획득
        return lockStrategy.executeWithLock(
            currentKey,
            waitTimeSec,
            leaseTime,
        ) {
            executeWithNestedLocks(
                sortedKeys,
                currentIndex + 1,
                remainingTimeoutMs - TimeUnit.SECONDS.toMillis(waitTimeSec),
                leaseTime,
                task,
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
        return nestedStrategyRequired.get()
            ?: throw IllegalStateException("Nested strategy required but not initialized after CAS failure")
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
            context,
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
            context,
        )
    }

    /** 편의 메서드: 초 단위 타임아웃 */
    @Deprecated("Use executeWithOrderedLocksAsync — sync blocks caller on task.get()")
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeoutSeconds: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
    ): T = executeWithOrderedLocks(keys, totalTimeoutSeconds, TimeUnit.SECONDS, leaseTime, task)

    // ==================== Async Internals (preferred) ====================

    /**
     * Async internal: 진입점. 정렬 후 iterative/nested 전략 분기.
     * Caller thread는 block되지 않는다. Strategy detection은 sync path와 동일하게
     * [requiresNestedStrategy]의 cached 값을 사용한다.
     */
    private fun <T> executeWithOrderedLocksInternalAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
        context: TaskContext,
    ): CompletableFuture<T> {
        val sortedKeys: java.util.List<String> = keys.sorted() as java.util.List<String>

        log.debug("[OrderedLock/Async] Acquiring {} locks in order: {}", sortedKeys.size, sortedKeys)

        return if (requiresNestedStrategy()) {
            log.debug("[OrderedLock/Async] Using nested callback strategy (MySQL Named Lock detected)")
            executeWithNestedLocksAsync(
                sortedKeys,
                0,
                timeUnit.toMillis(totalTimeout),
                leaseTime,
                supplier,
            )
        } else {
            executeWithIterativeStrategyAsync(sortedKeys, totalTimeout, timeUnit, leaseTime, supplier)
        }
    }

    /** Redisson용 반복 패턴 전략 — CompletableFuture 체인. */
    private fun <T> executeWithIterativeStrategyAsync(
        sortedKeys: java.util.List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val deadlineNanos = System.nanoTime() + timeUnit.toNanos(totalTimeout)
        @Suppress("USELESS_CAST")
        val acquiredLocks: java.util.List<String> = java.util.ArrayList<String>() as java.util.List<String>

        return acquireLocksAndExecuteAsync(sortedKeys, deadlineNanos, leaseTime, supplier, acquiredLocks)
            .whenComplete { _, _ -> releaseLocksInReverseOrderAsync(acquiredLocks) }
    }

    /**
     * 순차 락 획득 → 마지막 락에서 supplier 실행. 각 단계는 `thenCompose`로
     * 체이닝되어 CF가 완료된 후 다음 락 획득을 시도한다. 중간에 실패하면
     * `whenComplete`로 acquiredLocks를 LIFO 해제.
     */
    private fun <T> acquireLocksAndExecuteAsync(
        sortedKeys: java.util.List<String>,
        deadlineNanos: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
        acquiredLocks: java.util.List<String>,
    ): CompletableFuture<T> {
        var current: CompletableFuture<T> = CompletableFuture.completedFuture(null)

        for (i in sortedKeys.indices) {
            val currentKey = sortedKeys[i]
            val previous = current
            current = previous.thenCompose {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    CompletableFuture.failedFuture(
                        DistributedLockException(
                            String.format("전체 락 타임아웃 초과: %d/%d 락 획득 중 [key=%s]", i, sortedKeys.size, currentKey),
                        ),
                    )
                } else {
                    val remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos)
                    val waitTimeSec = Math.max(1, Math.min(remainingSeconds, 10))

                    log.debug(
                        "[OrderedLock/Async] Acquiring lock {}/{}: {} (remaining: {}ms)",
                        i + 1,
                        sortedKeys.size,
                        currentKey,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    )

                    lockStrategy.tryLockImmediatelyAsync(currentKey, leaseTime)
                        .thenCompose { acquired ->
                            if (!acquired) {
                                CompletableFuture.failedFuture(
                                    DistributedLockException(
                                        String.format("락 획득 실패: %s (waited %ds)", currentKey, waitTimeSec),
                                    ),
                                )
                            } else {
                                acquiredLocks.add(currentKey)
                                if (i == sortedKeys.size - 1) {
                                    supplier()
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    (CompletableFuture.completedFuture(null) as CompletableFuture<T>)
                                }
                            }
                        }
                }
            }
        }

        return current
    }

    /**
     * MySQL Named Lock용 중첩 콜백 전략 — async.
     * 각 락 획득 콜백 안에서 다음 락 획득을 thenCompose로 체이닝한다.
     */
    private fun <T> executeWithNestedLocksAsync(
        sortedKeys: java.util.List<String>,
        currentIndex: Int,
        remainingTimeoutMs: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        if (currentIndex >= MAX_NESTED_DEPTH) {
            return CompletableFuture.failedFuture(
                DistributedLockException(
                    String.format("중첩 락 깊이 초과: 최대 %d개까지 지원 (요청: %d개)", MAX_NESTED_DEPTH, sortedKeys.size),
                ),
            )
        }

        if (currentIndex >= sortedKeys.size) {
            log.info("[OrderedLock/Nested/Async] All {} locks acquired, executing supplier", sortedKeys.size)
            return supplier()
        }

        val currentKey = sortedKeys[currentIndex]
        val waitTimeSec = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingTimeoutMs))

        log.debug(
            "[OrderedLock/Nested/Async] Acquiring lock {}/{}: {} (remaining: {}ms)",
            currentIndex + 1,
            sortedKeys.size,
            currentKey,
            remainingTimeoutMs,
        )

        return lockStrategy.executeWithLockAsync(
            currentKey,
            waitTimeSec,
            leaseTime,
        ) {
            executeWithNestedLocksAsync(
                sortedKeys,
                currentIndex + 1,
                remainingTimeoutMs - TimeUnit.SECONDS.toMillis(waitTimeSec),
                leaseTime,
                supplier,
            )
        }
    }

    /**
     * LIFO 순서로 락 해제 — 비동기. each unlock returns a CF, chain으로 순서 보장.
     * 예외가 나도 다음 unlock을 시도하도록 `exceptionally`로 흡수.
     */
    private fun releaseLocksInReverseOrderAsync(acquiredLocks: List<String>) {
        var chain: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        for (i in acquiredLocks.size - 1 downTo 0) {
            val lockKey = acquiredLocks[i]
            chain = chain.thenCompose { lockStrategy.unlockAsync(lockKey) }
                .exceptionally { ex ->
                    log.warn("[OrderedLock/Async] Failed to release lock {}: {}", lockKey, ex.message)
                    null
                }
        }
        // fire-and-forget: 체인은 best-effort로 background에서 완료된다.
        // whenComplete는 supplier result와 무관하게 lock release를 보장한다.
    }

    companion object {
        private const val MAX_NESTED_DEPTH = 10 // P1-YELLOW-01: 스택 깊이 제한
        private val log = org.slf4j.LoggerFactory.getLogger(OrderedLockExecutor::class.java)
    }

    // P1-BLUE-01: 전략 캐싱 (Lock-Free 초기화)
    private val nestedStrategyRequired = AtomicReference<Boolean?>()
}

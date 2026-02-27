package maple.expectation.infrastructure.lock

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.common.function.ThrowingSupplierUtils
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.error.exception.base.ClientBaseException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.util.ExceptionUtils
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.lang.Nullable
import org.springframework.stereotype.Component
import java.util.List
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * 회복력 있는 락 전략 (Redis 우선, 실패 시 MySQL로 복구)
 */
@Primary
@Component
@ConditionalOnProperty(name = ["lock.impl"], havingValue = "redis", matchIfMissing = true)
class ResilientLockStrategy(
    @Qualifier("redisDistributedLockStrategy")
    private val redisLockStrategy: LockStrategy,
    @param:Nullable private val mysqlLockStrategy: LockStrategy?,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    logicExecutor: LogicExecutor,
    private val fallbackMetrics: LockFallbackMetrics
) : AbstractLockStrategy(logicExecutor) {

    private val circuitBreaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("redisLock")

    init {
        if (mysqlLockStrategy == null) {
            log.warn(
                "⚠️ [ResilientLockStrategy] MySQL Fallback 비활성화: lockJdbcTemplate 빈 없음. Redis-only 모드로 동작합니다."
            )
        }
    }

    // ========================================
    // 핵심 메서드: executeWithLock Override
    // ========================================

    /**
     * Tiered Lock 실행 (Redis → MySQL Fallback)
     */
    override fun <T> executeWithLock(
        key: String,
        waitTime: Long,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        val originalKey = removeLockPrefix(key)
        val context = TaskContext.of("ResilientLock", "ExecuteWithLock", originalKey)

        return executor.executeWithFallback(
            // Redis tier 전체 실행 (락+task+해제)
            {
                circuitBreaker.executeCheckedSupplier {
                    redisLockStrategy.executeWithLock(originalKey, waitTime, leaseTime, task)
                }
            },
    // 예외 분기: Function<Throwable, T> (throws 불가, checked는 fail-fast)
            { t ->
                handleFallback(
                    t,
                    originalKey,
                    "executeWithLock"
                ) { mysqlLockStrategy!!.executeWithLock(originalKey, waitTime, leaseTime, task) }
            },
            context
        )
    }

    /**
     * [Tier 1: Redis] 락 획득만 시도 → 실패 시 [Tier 2: MySQL] 복구
     */
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        val originalKey = removeLockPrefix(lockKey)
        val context = TaskContext.of("ResilientLock", "TryLock", lockKey)

        // P1 Fix: tryLockImmediately() 사용 (락 획득만, 해제 안 함)
        return executor.executeWithFallback(
            {
                circuitBreaker.executeCheckedSupplier {
                    redisLockStrategy.tryLockImmediately(originalKey, leaseTime)
                }
            },
            { t -> handleTryLockFallback(t, originalKey, leaseTime) },
            context
        )
    }

    /**
     * tryLock 전용 fallback 처리
     */
    private fun handleTryLockFallback(t: Throwable, key: String, leaseTime: Long): Boolean {
        val cause = ExceptionUtils.unwrapAsyncException(t)!!

        // Biz 예외: 즉시 전파
        if (cause is ClientBaseException) {
            throwAsRuntime(cause)
            @Suppress("UNREACHABLE_CODE")
            return false // unreachable
        }

        // Infra 예외: MySQL fallback 시도 (UnsupportedOperationException 예상)
        if (isInfrastructureException(cause)) {
            log.warn(
                "[TieredLock:tryLock] Redis failed, MySQL fallback 불가 (세션 기반). " +
                    "key={}, state={}, cause={}:{}",
                key,
                circuitBreaker.state,
                cause.javaClass.simpleName,
                cause.message ?: ""
            )
            // MySQL은 tryLockImmediately 지원 불가 → 락 획득 실패
            throw DistributedLockException(
                "Tiered Lock 획득 실패: Redis 불가 + MySQL 세션 기반으로 fallback 불가 [key=$key]",
                cause
            )
        }

        // Unknown: 즉시 전파
        log.error(
            "[TieredLock:tryLock] Unknown exception. key={}, cause={}:{}",
            key,
            cause.javaClass.name,
            cause.message ?: "",
            cause
        )
        throwAsRuntime(cause)
        @Suppress("UNREACHABLE_CODE")
        return false // unreachable
    }

    // ========================================
    // 예외 필터링 헬퍼 메서드
    // ========================================

    /** 인프라 예외 여부 판별 */
    private fun isInfrastructureException(cause: Throwable): Boolean {
        return cause is DistributedLockException ||
            cause is io.github.resilience4j.circuitbreaker.CallNotPermittedException ||
            cause is RedisException ||
            cause is RedisTimeoutException
    }

    /** lock: prefix 제거 */
    private fun removeLockPrefix(lockKey: String): String {
        return if (lockKey.startsWith("lock:")) lockKey.substring(5) else lockKey
    }

    /**
     * fallback 분기 (throws / try-catch 없음)
     */
    private fun <T> handleFallback(
        t: Throwable,
        key: String,
        op: String,
        mysqlFallback: ThrowingSupplier<T>
    ): T {
        val cause = ExceptionUtils.unwrapAsyncException(t)!!

        // InterruptedException은 Lock 도메인 예외로 정규화
        if (cause is InterruptedException) {
            Thread.currentThread().interrupt()
            throw DistributedLockException("락 획득/실행 중 인터럽트 [op=$op, key=$key]", cause)
        }

        // 1) Biz 예외: fallback 절대 금지
        if (cause is ClientBaseException) {
            throwAsRuntime(cause)
        }

        // 2) Infra 예외: MySQL fallback 시도
        if (isInfrastructureException(cause)) {
            // MySQL 전략 없으면 Redis 실패로 즉시 전파
            if (mysqlLockStrategy == null) {
                log.error(
                    "[TieredLock:{}] Redis failed + MySQL unavailable (Redis-only mode). key={}, state={}, cause={}:{}",
                    op,
                    key,
                    circuitBreaker.state,
                    cause.javaClass.simpleName,
                    cause.message ?: ""
                )
                throw DistributedLockException(
                    "락 획득/실행 실패: Redis 불가 + MySQL Fallback 비활성화 [op=$op, key=$key]",
                    cause
                )
            }

            log.warn(
                "[TieredLock:{}] Redis failed -> MySQL fallback. key={}, state={}, cause={}:{}",
                op,
                key,
                circuitBreaker.state,
                cause.javaClass.simpleName,
                cause.message ?: ""
            )
            return ThrowingSupplierUtils.getUnchecked(mysqlFallback)
        }

        // 3) Unknown: 즉시 전파 (버그 조기 발견)
        log.error(
            "[TieredLock:{}] Unknown exception -> propagate. key={}, cause={}:{}",
            op,
            key,
            cause.javaClass.name,
            cause.message ?: "",
            cause
        )
        throwAsRuntime(cause)
    }

    /** RuntimeException/Error는 원형 전파, checked Throwable은 정책 위반으로 fail-fast */
    private fun throwAsRuntime(t: Throwable): Nothing {
        when (t) {
            is Error -> throw t
            is RuntimeException -> throw t
            else -> throw IllegalStateException(
                "Unexpected checked Throwable (policy violation): " + t.javaClass.name,
                t
            )
        }
    }

    // ========================================
    // unlock / immediate
    // ========================================

    override fun unlockInternal(lockKey: String) {
        val originalKey = removeLockPrefix(lockKey)
        val context = TaskContext.of("ResilientLock", "UnlockInternal", lockKey)

        executor.executeWithFinally(
            {
                circuitBreaker.executeRunnable { redisLockStrategy.unlock(originalKey) }
                null
            },
            { unlockMySqlIfAvailable(originalKey) },
            context
        )
    }

    private fun unlockMySqlIfAvailable(key: String) {
        Optional.ofNullable(mysqlLockStrategy).ifPresent { strategy: LockStrategy -> strategy.unlock(key) }
    }

    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        return executor.executeOrDefault(
            { this.tryLock(buildLockKey(key), 0, leaseTime) },
            false,
            TaskContext.of("ResilientLock", "TryLockImmediate", key)
        )
    }

    override fun shouldUnlock(lockKey: String): Boolean {
        return true
    }

    // ========================================
    // [P0-N02] 다중 락 순서 보장 실행
    // ========================================

    /**
     * [P0-N02] 다중 락 순서 보장 실행 (Redis → MySQL Fallback)
     */
    override fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        val keysStr = java.lang.String.join(",", keys)
        val context = TaskContext.of("ResilientLock", "OrderedExecute", keysStr)

        return executor.executeWithFallback(
            // Redis tier: 순서 보장 다중 락 실행
            {
                circuitBreaker.executeCheckedSupplier {
                    redisLockStrategy.executeWithOrderedLocks(keys, totalTimeout, timeUnit, leaseTime, task)
                }
            },
    // MySQL fallback: 순서 보장 다중 락 실행
            { t ->
                handleOrderedLockFallback(
                    t,
                    keysStr
                ) {
                    mysqlLockStrategy!!.executeWithOrderedLocks(
                        keys,
                        totalTimeout,
                        timeUnit,
                        leaseTime,
                        task
                    )
                }
            },
            context
        )
    }

    /** 다중 락 Fallback 처리 */
    private fun <T> handleOrderedLockFallback(
        t: Throwable,
        keys: String,
        mysqlFallback: ThrowingSupplier<T>
    ): T {
        val cause = ExceptionUtils.unwrapAsyncException(t)!!

        // InterruptedException 처리
        if (cause is InterruptedException) {
            Thread.currentThread().interrupt()
            throw DistributedLockException("다중 락 획득 중 인터럽트 [keys=$keys]", cause)
        }

        // 비즈니스 예외: 즉시 전파
        if (cause is ClientBaseException) {
            throwAsRuntime(cause)
        }

        // 인프라 예외: MySQL Fallback 시도
        if (isInfrastructureException(cause)) {
            // MySQL 전략 없으면 Redis 실패로 즉시 전파
            if (mysqlLockStrategy == null) {
                log.error(
                    "[TieredLock:OrderedExecute] Redis failed + MySQL unavailable (Redis-only mode). keys={}, state={}, cause={}:{}",
                    keys,
                    circuitBreaker.state,
                    cause.javaClass.simpleName,
                    cause.message ?: ""
                )
                throw DistributedLockException(
                    "다중 락 획득 실패: Redis 불가 + MySQL Fallback 비활성화 [keys=$keys]",
                    cause
                )
            }

            log.warn(
                "[TieredLock:OrderedExecute] Redis failed -> MySQL fallback. keys={}, state={}, cause={}:{}",
                keys,
                circuitBreaker.state,
                cause.javaClass.simpleName,
                cause.message ?: ""
            )
            return ThrowingSupplierUtils.getUnchecked(mysqlFallback)
        }

        // Unknown: 즉시 전파
        log.error(
            "[TieredLock:OrderedExecute] Unknown exception -> propagate. keys={}, cause={}:{}",
            keys,
            cause.javaClass.name,
            cause.message ?: "",
            cause
        )
        throwAsRuntime(cause)
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ResilientLockStrategy::class.java)
    }
}

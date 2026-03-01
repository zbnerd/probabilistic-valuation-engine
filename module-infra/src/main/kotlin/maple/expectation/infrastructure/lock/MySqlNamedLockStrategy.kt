package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DatabaseNamedLockException
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.ArrayDeque
import java.util.Deque

/**
 * MySQL Named Lock 전략
 */
@Component
@ConditionalOnBean(name = ["lockJdbcTemplate"])
class MySqlNamedLockStrategy(
    @Qualifier("lockJdbcTemplate")
    private val lockJdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    private val lockOrderMetrics: LockOrderMetrics,
    private val lockMetrics: LockMetrics
) : LockStrategy {

    /**
     * [P0-BLUE-01] ThreadLocal 메모리 누수 방지
     */
    private val acquiredLocks: ThreadLocal<Deque<String>> = ThreadLocal.withInitial { ArrayDeque() }

    override fun <T> executeWithLock(key: String, waitTime: Long, leaseTime: Long, task: ThrowingSupplier<T>): T {
        val lockKey = buildLockKey(key)
        val context = TaskContext.of("Lock", "MySqlExecute", key)

        // [패턴 6] 최상단에서 모든 예외를 도메인 예외로 세탁
        return executor.executeWithTranslation(
            { this.executeInSession(lockKey, waitTime, task, context) },
            ExceptionTranslator.forLock(),
            context
        )
    }

    override fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T {
        return executeWithLock(key, 10, 20, task)
    }

    /** 평탄화의 핵심: 람다 중첩과 try-catch를 메서드 추출로 해결 */
    private fun <T> executeInSession(
        lockKey: String,
        waitTime: Long,
        task: ThrowingSupplier<T>,
        context: TaskContext
    ): T {
        // 1. 명시적 캐스팅으로 람다 모호성 해결 (괄호 한 번만 열림)
        return lockJdbcTemplate.execute(
            ConnectionCallback<T> { conn -> this.runLogicWithPinnedSession(conn, lockKey, waitTime, task, context) }
        )!!
    }

    /** P0: 세션 고정 환경에서 로직 실행 */
    private fun <T> runLogicWithPinnedSession(
        conn: Connection,
        lockKey: String,
        waitTime: Long,
        task: ThrowingSupplier<T>,
        context: TaskContext
    ): T {
        val sessionJdbc = JdbcTemplate(SingleConnectionDataSource(conn, true))

        // [패턴 1] try-finally 키워드 대신 executeWithFinally 사용
        // [P0-BLUE-01] ThreadLocal 정리를 finally에서 수행
        return executor.executeWithFinally(
            { this.acquireAndExecute(sessionJdbc, lockKey, waitTime, task) },
            { this.releaseAndCleanup(sessionJdbc, lockKey, context) },
            context
        )
    }

    /**
     * 락 획득 및 작업 실행
     */
    @Throws(Throwable::class)
    private fun <T> acquireAndExecute(
        sessionJdbc: JdbcTemplate,
        lockKey: String,
        waitTime: Long,
        task: ThrowingSupplier<T>
    ): T {
        // 1. Lock Ordering 검증 (Deadlock 위험 감지)
        validateLockOrder(lockKey)

        // 2. 락 획득 시도
        if (!tryAcquire(sessionJdbc, lockKey, waitTime)) {
            throw DistributedLockException("락 획득 타임아웃: $lockKey")
        }

        // 3. 락 획득 성공 - 추적 시작
        trackLockAcquisition(lockKey)
        log.info("🔓 [MySQL Lock] '{}' 획득 성공", lockKey)

        // 4. 작업 실행
        return task.get()
    }

    /**
     * [P0-N09] 락 순서 검증
     */
    private fun validateLockOrder(lockKey: String) {
        val acquired: Deque<String> = acquiredLocks.get()
        if (!acquired.isEmpty()) {
            val lastLock: String = acquired.peekLast()
            // 알파벳순으로 현재 락이 마지막 락보다 앞서면 위반
            if (lockKey.compareTo(lastLock) < 0) {
                // 메트릭 기록 (경고 로그는 LockOrderMetrics에서 처리)
                lockOrderMetrics.recordViolation(lockKey, lastLock)
            }
        }
    }

    /**
     * 락 획득 추적
     */
    private fun trackLockAcquisition(lockKey: String) {
        acquiredLocks.get().addLast(lockKey)
        lockOrderMetrics.recordAcquisition(lockKey)
    }

    /**
     * [P0-BLUE-01] 락 해제 및 ThreadLocal 정리
     */
    private fun releaseAndCleanup(sessionJdbc: JdbcTemplate, lockKey: String, context: TaskContext) {
        // 1. 락 해제
        releaseLock(sessionJdbc, lockKey, context)

        // 2. ThreadLocal 정리 (메모리 누수 방지)
        cleanupLockTracking(lockKey)
    }

    /**
     * [P0-BLUE-01] 락 추적 정리 - 메모리 누수 방지 핵심
     */
    private fun cleanupLockTracking(lockKey: String) {
        val acquired: Deque<String> = acquiredLocks.get()
        acquired.removeLastOccurrence(lockKey)
        lockOrderMetrics.recordRelease(lockKey)

        // 빈 경우 ThreadLocal 완전 제거 (Critical: 메모리 누수 방지)
        if (acquired.isEmpty()) {
            acquiredLocks.remove()
        }
    }

    private fun tryAcquire(sessionJdbc: JdbcTemplate, lockKey: String, waitTime: Long): Boolean {
        val acquiredFlag: Int? = sessionJdbc.queryForObject(
            "SELECT GET_LOCK(?, ?)",
            Int::class.java,
            lockKey,
            waitTime
        ) ?: throw DatabaseNamedLockException("GET_LOCK", lockKey, waitTime)

        return acquiredFlag == 1
    }

    private fun releaseLock(sessionJdbc: JdbcTemplate, lockKey: String, context: TaskContext) {
        executor.executeVoidJava(
            {
                val r: Int? = sessionJdbc.queryForObject(
                    "SELECT RELEASE_LOCK(?)",
                    Int::class.java,
                    lockKey
                )

                if (r == null) {
                    throw DatabaseNamedLockException("RELEASE_LOCK", lockKey, null)
                }
                if (r != 1) { // 0 포함
                    throw DatabaseNamedLockException("RELEASE_LOCK(non-owner)", lockKey, null)
                }

                log.debug("🔒 [MySQL Lock] '{}' 해제 완료", lockKey)
            },
            context
        )
    }

    /**
     * MySQL Named Lock은 세션 기반이므로 "획득만" 패턴 지원 불가
     */
    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        throw UnsupportedOperationException(
            "MySQL Named Lock은 세션 기반이므로 tryLockImmediately() 지원 불가. " +
            "executeWithLock()을 사용하세요."
        )
    }

    override fun unlock(key: String) {
        log.debug("ℹ️ [MySQL Lock] unlock() 호출됨 (세션 기반이라 실제 동작 안 함)")
    }

    private fun buildLockKey(key: String): String {
        return "maple_lock:$key"
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(MySqlNamedLockStrategy::class.java)
    }
}

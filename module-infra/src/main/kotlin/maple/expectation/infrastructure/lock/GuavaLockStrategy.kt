package maple.expectation.infrastructure.lock

import com.google.common.util.concurrent.Striped
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock

/** Guava Striped Lock 전략 (테스트 환경 전용 - 100% 평탄화 완료) */
@Component
@Profile("test")
class GuavaLockStrategy(executor: LogicExecutor) : AbstractLockStrategy(executor) {

    private val locks: Striped<Lock> = Striped.lock(128)

    @Throws(Throwable::class)
    override fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long): Boolean {
        // 부모 클래스(AbstractLockStrategy)의 템플릿 메서드에서 호출됨
        return locks.get(lockKey).tryLock(waitTime, TimeUnit.SECONDS)
    }

    override fun unlockInternal(lockKey: String) {
        locks.get(lockKey).unlock()
    }

    override fun shouldUnlock(lockKey: String): Boolean {
        // 로컬 락은 상태 체크 대신 해제 시도 시 발생하는 예외를 Executor가 처리하도록 위임
        return true
    }

    override fun buildLockKey(key: String): String {
        return key
    }

    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        // [패턴 3] executeOrDefault를 사용하여 try-catch 없이 즉시 획득 시도
        return executor.executeOrDefault(
            { locks.get(key).tryLock() },
            false,
            TaskContext.of("Lock", "GuavaTryImmediate", key)
        )
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(GuavaLockStrategy::class.java)
    }
}

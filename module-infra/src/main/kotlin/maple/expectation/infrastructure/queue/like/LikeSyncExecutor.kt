package maple.expectation.infrastructure.queue.like

import maple.expectation.error.exception.LikeSyncCircuitOpenException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 좋아요 동기화 실행기 (Issue #48: Batch Update 지원)
 *
 * <p><b>#664 Deprecated:</b> DB Trigger(fn_like_count_trigger)가 character_like INSERT/DELETE 시
 * like_count를 자동 증감하므로 이 클래스의 메서드들은 더 이상 실제 동작을 수행하지 않습니다.
 * V104 reconciliation이 기존 count를 보정했습니다.
 */
@Deprecated("#664: DB Trigger handles like_count atomicity")
@Component
open class LikeSyncExecutor {

    companion object {
        private val log = LoggerFactory.getLogger(LikeSyncExecutor::class.java)
    }

    /**
     * @deprecated #664: DB Trigger가 like_count를 자동 증감하므로 no-op.
     */
    @Deprecated("#664: DB Trigger handles like_count atomicity")
    fun executeIncrement(userIgn: String, count: Long) {
        log.info("[LikeSyncExecutor] Skipped deprecated increment: userIgn={}, count={} (trigger handles count)", userIgn, count)
    }

    /**
     * @deprecated #664: DB Trigger가 like_count를 자동 증감하므로 no-op.
     */
    @Deprecated("#664: DB Trigger handles like_count atomicity")
    fun executeIncrementBatch(entries: List<Map.Entry<String, Long>>) {
        if (entries.isEmpty()) return
        log.info("[LikeSyncExecutor] Skipped deprecated batch increment: {} entries (trigger handles count)", entries.size)
    }

    /**
     * CircuitBreaker Fallback (서킷 오픈 시)
     */
    @SuppressWarnings("unused") // CircuitBreaker fallback으로 사용됨
    fun batchFallback(entries: List<Map.Entry<String, Long>>, t: Throwable) {
        log.warn(
            "[LikeSync] Circuit OPEN, batch skipped ({} entries): {}",
            entries.size,
            t.message,
        )
        throw LikeSyncCircuitOpenException(t)
    }
}

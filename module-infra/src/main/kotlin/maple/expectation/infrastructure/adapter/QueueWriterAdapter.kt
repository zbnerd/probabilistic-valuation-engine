package maple.expectation.infrastructure.adapter

import maple.expectation.core.port.out.QueueWriterPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * No-op QueueWriterPort 구현체
 *
 * Redis 제거 후 큐 라이터 기능 비활성화 상태.
 * LowPriorityQueueWriter 정상 시작을 위해 빈 등록만 수행.
 */
@Component
class QueueWriterAdapter : QueueWriterPort {

    override fun addLowPriorityTask(userIgn: String): Boolean {
        log.debug("[QueueWriter] No-op implementation - accepting task for: {}", userIgn)
        return true
    }

    override fun addHighPriorityTask(userIgn: String, forceRecalculation: Boolean): Boolean {
        log.debug("[QueueWriter] No-op implementation - accepting high priority task for: {}", userIgn)
        return true
    }

    override fun size(): Int = 0

    companion object {
        private val log = LoggerFactory.getLogger(QueueWriterAdapter::class.java)
    }
}

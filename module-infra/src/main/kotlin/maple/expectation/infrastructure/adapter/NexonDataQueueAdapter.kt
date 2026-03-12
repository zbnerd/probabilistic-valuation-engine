package maple.expectation.infrastructure.adapter

import maple.expectation.core.port.out.MessageQueue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * No-op MessageQueue implementation for Nexon data queue.
 *
 * <p>Redis 제거 후 큐 기능 비활성화 상태.
 * BatchWriter 정상 시작을 위해 빈 등록만 수행.
 *
 * @see MessageQueue
 * @see maple.expectation.infrastructure.scheduler.BatchWriter
 */
@Component
@Qualifier("nexonDataQueue")
class NexonDataQueueAdapter : MessageQueue<String> {

    override fun offer(message: String): Boolean {
        log.debug("[NexonDataQueue] No-op implementation - accepting message")
        return true
    }

    override fun poll(): String? {
        log.debug("[NexonDataQueue] No-op implementation - returning null")
        return null
    }

    override fun size(): Int = 0

    companion object {
        private val log = LoggerFactory.getLogger(NexonDataQueueAdapter::class.java)
    }
}

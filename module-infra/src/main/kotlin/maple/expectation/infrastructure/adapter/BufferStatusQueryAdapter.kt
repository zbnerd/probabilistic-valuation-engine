package maple.expectation.infrastructure.adapter

import maple.expectation.core.port.out.BufferStatusQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * No-op BufferStatusQuery implementation.
 *
 * <p>Provides stub implementation for buffer status queries.
 * Returns zero pending count since Redis-based buffer was removed.
 *
 * @see BufferStatusQuery
 */
@Component
class BufferStatusQueryAdapter : BufferStatusQuery {

    override fun getTotalPendingCount(): Long {
        log.debug("[BufferStatusQuery] No-op implementation - returning 0")
        return 0L
    }

    companion object {
        private val log = LoggerFactory.getLogger(BufferStatusQueryAdapter::class.java)
    }
}

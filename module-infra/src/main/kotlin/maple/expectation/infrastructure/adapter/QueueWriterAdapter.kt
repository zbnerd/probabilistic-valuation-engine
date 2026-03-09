package maple.expectation.infra.adapter

import maple.expectation.core.port.out.QueueWriterPort
import maple.expectation.infrastructure.queue.priority.PriorityCalculationQueue
import org.springframework.stereotype.Component

/**
 * QueueWriterPort의 Redis 기반 구현체
 *
 * <h3>Wiring</h3>
 * module-infra의 PriorityCalculationQueue에 위임
 */
@Component
class QueueWriterAdapter(
    private val delegate: PriorityCalculationQueue,
) : QueueWriterPort {

    override fun addLowPriorityTask(userIgn: String): Boolean = delegate.addLowPriorityTask(userIgn)

    override fun addHighPriorityTask(userIgn: String, forceRecalculation: Boolean): Boolean = delegate.addHighPriorityTask(userIgn, forceRecalculation)

    override fun size(): Int {
        val (high, low) = delegate.getQueueSize()
        return high + low
    }
}

package maple.expectation.infrastructure.queue.priority

import io.micrometer.core.instrument.MeterRegistry
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component

/**
 * Priority-based task queue for equipment calculation
 *
 * <h3>Queue Structure</h3>
 * Uses two separate Redis lists for priority:
 * - High Priority: "priority:calc:high" (force recalculation, user requests)
 * - Low Priority: "priority:calc:low" (batch refresh, background tasks)
 *
 * @param redissonClient Redis client
 * @param meterRegistry Metrics registry
 */
@Component
class PriorityCalculationQueue(
    private val redissonClient: RedissonClient,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private const val HIGH_PRIORITY_QUEUE = "priority:calc:high"
        private const val LOW_PRIORITY_QUEUE = "priority:calc:low"
        private const val MAX_QUEUE_SIZE = 10_000
    }

    private val highPriorityQueue = redissonClient.getBlockingQueue<String>(HIGH_PRIORITY_QUEUE)
    private val lowPriorityQueue = redissonClient.getBlockingQueue<String>(LOW_PRIORITY_QUEUE)

    /**
     * Add low priority task to the queue
     *
     * @param userIgn User IGN to process
     * @return true if added successfully, false if queue is full (backpressure)
     */
    fun addLowPriorityTask(userIgn: String): Boolean {
        return try {
            if (lowPriorityQueue.size >= MAX_QUEUE_SIZE) {
                meterRegistry.counter("priority.queue.rejected", "priority", "low").increment()
                return false
            }
            val result = lowPriorityQueue.offer(userIgn)
            if (result) {
                meterRegistry.counter("priority.queue.added", "priority", "low").increment()
            }
            result
        } catch (e: Exception) {
            meterRegistry.counter("priority.queue.error", "priority", "low").increment()
            false
        }
    }

    /**
     * Add high priority task to the queue
     *
     * @param userIgn User IGN to process
     * @param forceRecalculation Whether to force recalculation (currently unused, kept for API compatibility)
     * @return true if added successfully, false if queue is full (backpressure)
     */
    fun addHighPriorityTask(userIgn: String, forceRecalculation: Boolean): Boolean {
        return try {
            if (highPriorityQueue.size >= MAX_QUEUE_SIZE) {
                meterRegistry.counter("priority.queue.rejected", "priority", "high").increment()
                return false
            }
            val result = highPriorityQueue.offer(userIgn)
            if (result) {
                meterRegistry.counter("priority.queue.added", "priority", "high").increment()
            }
            result
        } catch (e: Exception) {
            meterRegistry.counter("priority.queue.error", "priority", "high").increment()
            false
        }
    }

    /**
     * Get current queue sizes
     */
    fun getQueueSize(): Pair<Int, Int> = Pair(highPriorityQueue.size, lowPriorityQueue.size)
}

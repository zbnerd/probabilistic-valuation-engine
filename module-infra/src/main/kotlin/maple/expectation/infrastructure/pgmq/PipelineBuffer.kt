package maple.expectation.infrastructure.pgmq

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe concurrent buffer for PGMQ pipeline architecture.
 *
 * Phase 1 results flow into this buffer, and a Drainer thread
 * micro-batches from it via [drain].
 *
 * @param T item type
 * @param microBatchSize default batch size for drain operations
 * @param maxBufferSize maximum items the buffer can hold
 */
class PipelineBuffer<T>(
    private val microBatchSize: Int = 10,
    private val maxBufferSize: Int = 500,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val count = AtomicInteger(0)

    fun offer(result: T): Boolean {
        while (true) {
            val current = count.get()
            if (current >= maxBufferSize) return false
            if (count.compareAndSet(current, current + 1)) {
                queue.add(result)
                return true
            }
        }
    }

    fun drain(maxItems: Int): List<T> {
        val batch = mutableListOf<T>()
        repeat(maxItems) {
            val item = queue.poll() ?: return batch
            batch.add(item)
            count.decrementAndGet()
        }
        return batch
    }

    fun drain(): List<T> = drain(microBatchSize)

    fun size(): Int = count.get()

    fun isFull(): Boolean = count.get() >= maxBufferSize
}

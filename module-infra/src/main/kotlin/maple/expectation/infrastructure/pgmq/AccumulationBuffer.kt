package maple.expectation.infrastructure.pgmq

/**
 * Time-based message accumulation buffer for sequential batch processing.
 *
 * Accumulates PGMQ messages for [bufferMs] milliseconds before flushing.
 * Designed for single-threaded access (called only from @Scheduled processMessages).
 *
 * @param T payload type
 * @param bufferMs time window in ms. 0 = immediate flush (parallel mode fallback).
 */
class AccumulationBuffer<T>(
    private val bufferMs: Long,
    private val maxSize: Int = 10_000,
) {
    private val messages = ArrayDeque<PgmqMessage<T>>()
    private var firstMessageTimeMs: Long = 0L

    fun addAll(newMessages: List<PgmqMessage<T>>) {
        if (newMessages.isEmpty()) return
        if (messages.isEmpty()) {
            firstMessageTimeMs = System.currentTimeMillis()
        }
        messages.addAll(newMessages)
    }

    fun shouldFlush(): Boolean {
        if (messages.isEmpty()) return false
        if (bufferMs <= 0) return true
        return System.currentTimeMillis() - firstMessageTimeMs >= bufferMs
    }

    fun drain(): List<PgmqMessage<T>> {
        val result = messages.toList()
        messages.clear()
        firstMessageTimeMs = 0L
        return result
    }

    fun isEmpty(): Boolean = messages.isEmpty()

    fun size(): Int = messages.size

    fun isFull(): Boolean = messages.size >= maxSize
}

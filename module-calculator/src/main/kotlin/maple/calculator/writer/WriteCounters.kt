package maple.calculator.writer

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe counters for the streaming write path.
 *
 * The producer coroutine increments [records] while draining the result flow.
 * The atomic counter lets asynchronous completion callbacks observe the final
 * value without extra locking.
 */
class WriteCounters {
    val records: AtomicLong = AtomicLong(0)
}

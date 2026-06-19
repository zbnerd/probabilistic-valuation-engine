package maple.calculator.writer

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe counters for the streaming write path.
 *
 * Producer (Flow.collect on a dedicated dispatcher) increments [records] and
 * [uncompressedBytes]. Consumer (CF callback from putStreamMultipart) reads
 * the final values. All fields are [AtomicLong] so producer and consumer
 * can update independently without locks.
 */
class WriteCounters {
    val records: AtomicLong = AtomicLong(0)
    val uncompressedBytes: AtomicLong = AtomicLong(0)
    val compressedBytes: AtomicLong = AtomicLong(0)
}

package maple.expectation.infrastructure.cache.tiered

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache

/**
 * Time-window batching for L2 cache writes.
 *
 * Accumulates put operations from concurrent threads, deduplicates them
 * (last-write-wins), and flushes as a single batched UPSERT after [flushIntervalMs].
 *
 * L1 is populated immediately on submit for read availability.
 * L2 writes are deferred and batched for throughput.
 *
 * Flow:
 * ```
 * Thread A: submit(k1, v1) ┐
 * Thread B: submit(k2, v2) ├─(10ms window)→ L2 batch UPSERT (k1,v1), (k2,v2)
 * Thread C: submit(k1, v3) ┘ (dedup: v3 overwrites v1 for k1)
 * ```
 *
 * @param l2Strategy    underlying L2 strategy for batch putAll()
 * @param l1            L1 cache for immediate put
 * @param ttlMinutes    TTL for all entries in this cache
 * @param flushIntervalMs time window to accumulate writes (default 10ms)
 * @param maxBatchSize  max entries per flush (default 500)
 */
class BatchL2WriteBuffer(
    private val l2Strategy: L2CacheStrategy,
    private val l1: Cache,
    private val ttlMinutes: Long,
    meterRegistry: MeterRegistry,
    private val executor: LogicExecutor,
    private val flushIntervalMs: Long = 10,
    private val maxBatchSize: Int = 500,
) {
    companion object {
        private val log = LoggerFactory.getLogger(BatchL2WriteBuffer::class.java)
        private val sharedScheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "batch-l2-write") }
    }

    private data class PendingWrite(
        val key: Any,
        val value: Any,
    )

    private val pending = ConcurrentLinkedQueue<PendingWrite>()
    private val flushScheduled = AtomicBoolean(false)

    private val batchCounter = meterRegistry.counter("cache.l2.batch.write", "op", "flush")
    private val batchSizeSummary = io.micrometer.core.instrument.DistributionSummary
        .builder("cache.l2.batch.write.size")
        .register(meterRegistry)

    /**
     * Submit a key-value pair for batched L2 write.
     *
     * L1 is populated immediately. L2 write is deferred to the next flush.
     * Null values are skipped for L2 (matching PostgresL2CacheAdapter behavior).
     */
    fun submit(key: Any, value: Any?) {
        l1.put(key, value)
        if (value == null) return
        pending.add(PendingWrite(key, value))
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            sharedScheduler.schedule({ flush() }, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        val batch = mutableListOf<PendingWrite>()
        while (batch.size < maxBatchSize) {
            val req = pending.poll() ?: break
            batch.add(req)
        }

        if (batch.isEmpty()) {
            flushScheduled.set(false)
            if (!pending.isEmpty()) scheduleFlush()
            return
        }

        // Deduplicate: last-write-wins
        val uniqueEntries = mutableMapOf<String, Any>()
        for (req in batch) {
            uniqueEntries[req.key.toString()] = req.value
        }

        executor.executeOrCatch(
            {
                val start = System.nanoTime()
                val entries = uniqueEntries.map { it.key to it.value }
                l2Strategy.putAll(entries, ttlMinutes)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

                batchCounter.increment()
                batchSizeSummary.record(entries.size.toDouble())

                if (log.isDebugEnabled || entries.size >= 50 || elapsedMs >= 100) {
                    log.info("[BatchL2Write] Flush: {} entries in {}ms", entries.size, elapsedMs)
                }
                null
            },
            { e ->
                log.warn("[BatchL2Write] Flush failed for {} entries: {}", uniqueEntries.size, e.message)
                null
            },
            TaskContext.of("BatchL2Write", "Flush", uniqueEntries.size.toString()),
        )

        if (!pending.isEmpty()) {
            sharedScheduler.schedule({ flush() }, 0, TimeUnit.MILLISECONDS)
        } else {
            flushScheduled.set(false)
            if (!pending.isEmpty()) scheduleFlush()
        }
    }
}

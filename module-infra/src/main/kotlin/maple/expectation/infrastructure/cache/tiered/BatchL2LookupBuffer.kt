package maple.expectation.infrastructure.cache.tiered

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache

/**
 * Time-window batching for L2 cache lookups.
 *
 * Accumulates L1-miss keys from concurrent threads, deduplicates them,
 * and flushes as a single batched L2 WHERE IN query after [flushIntervalMs].
 *
 * Flow:
 * ```
 * Thread A: submit(k1) ┐
 * Thread B: submit(k2) ├─(10ms window)→ L2 WHERE IN (k1,k2) → L1 backfill → complete futures
 * Thread C: submit(k1) ┘ (dedup: joins existing future for k1)
 * ```
 *
 * @param l2Strategy   underlying L2 strategy for batch getAll()
 * @param l1           L1 cache for backfill after L2 hit
 * @param flushIntervalMs time window to accumulate keys (default 10ms)
 * @param maxBatchSize max keys per flush (default 500)
 */
class BatchL2LookupBuffer(
    private val l2Strategy: L2CacheStrategy,
    private val l1: Cache,
    meterRegistry: MeterRegistry,
    private val flushIntervalMs: Long = 10,
    private val maxBatchSize: Int = 500,
) {
    companion object {
        private val log = LoggerFactory.getLogger(BatchL2LookupBuffer::class.java)
        private val sharedScheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "batch-l2-flush") }
    }

    private data class PendingRequest(
        val key: Any,
        val future: CompletableFuture<Any?>,
    )

    private val pending = ConcurrentLinkedQueue<PendingRequest>()
    private val inflight = ConcurrentHashMap<Any, CompletableFuture<Any?>>()
    private val flushScheduled = AtomicBoolean(false)

    private val batchCounter = meterRegistry.counter("cache.l2.batch", "op", "flush")
    private val batchSizeSummary = io.micrometer.core.instrument.DistributionSummary
        .builder("cache.l2.batch.size")
        .register(meterRegistry)
    private val dedupCounter = meterRegistry.counter("cache.l2.batch", "op", "dedup")
    private val batchLatencyMs = AtomicLong(0)

    /**
     * Submit a key for batched L2 lookup.
     *
     * Returns a future that completes when the next flush resolves this key.
     * If the same key is already pending (inflight dedup), returns the existing future.
     */
    fun submit(key: Any): CompletableFuture<Any?> {
        val existing = inflight[key]
        if (existing != null) {
            dedupCounter.increment()
            return existing
        }

        val future = CompletableFuture<Any?>()
        val prev = inflight.putIfAbsent(key, future)
        if (prev != null) {
            dedupCounter.increment()
            return prev
        }

        pending.add(PendingRequest(key, future))
        scheduleFlush()
        return future
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            sharedScheduler.schedule({ flush() }, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        val batch = mutableListOf<PendingRequest>()
        while (batch.size < maxBatchSize) {
            val req = pending.poll() ?: break
            batch.add(req)
        }

        if (batch.isEmpty()) {
            flushScheduled.set(false)
            if (!pending.isEmpty()) scheduleFlush()
            return
        }

        val uniqueKeys = batch.map { it.key }.distinctBy { it.toString() }
        val keyToOriginal = uniqueKeys.associateBy { it.toString() }
        val keyStrings = uniqueKeys.map { it.toString() }

        try {
            val start = System.nanoTime()
            val results = l2Strategy.getAll(keyStrings, Any::class.java)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

            batchCounter.increment()
            batchSizeSummary.record(uniqueKeys.size.toDouble())
            batchLatencyMs.set(elapsedMs)

            if (log.isDebugEnabled || uniqueKeys.size >= 50 || elapsedMs >= 100) {
                log.info("[BatchL2] Flush: {} unique keys → {} hits in {}ms", uniqueKeys.size, results.size, elapsedMs)
            }

            for ((keyStr, value) in results) {
                val originalKey = keyToOriginal[keyStr] ?: continue
                l1.put(originalKey, value)
            }

            for (req in batch) {
                val value = results[req.key.toString()]
                req.future.complete(value)
                inflight.remove(req.key)
            }
        } catch (e: Exception) {
            log.warn("[BatchL2] Flush failed for {} keys: {}", uniqueKeys.size, e.message)
            for (req in batch) {
                req.future.completeExceptionally(e)
                inflight.remove(req.key)
            }
        }

        if (!pending.isEmpty()) {
            sharedScheduler.schedule({ flush() }, 0, TimeUnit.MILLISECONDS)
        } else {
            flushScheduled.set(false)
            if (!pending.isEmpty()) scheduleFlush()
        }
    }
}

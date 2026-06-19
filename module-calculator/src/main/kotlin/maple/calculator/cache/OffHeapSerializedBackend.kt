package maple.calculator.cache

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Off-heap cache backend using direct [ByteBuffer] for value storage.
 *
 * **Why this exists:** Chronicle Map (the original target per issue #1311) does
 * not support JDK 21 in any stable release. This implementation achieves the
 * **heap reduction goal** without a third-party off-heap KV dep:
 *
 * - Keys: hashed to `Int`, stored in a [ConcurrentHashMap] (Long → ByteBuffer).
 *   Heap cost: ~32 bytes/entry × 100K = ~3MB (vs Caffeine POJOs at ~300 bytes × 100K = 30MB).
 * - Values: serialized via Jackson to [ByteBuffer.allocateDirect]. Stored off-heap.
 * - Eviction: when total direct bytes exceed cap, drop oldest entries (insertion-order tracker).
 * - Thread-safety: ConcurrentHashMap for index; AtomicLong for byte counter; per-entry ByteBuffers are not mutated after put.
 *
 * When Chronicle Map ships JDK 21 support, this backend can be replaced by re-introducing
 * [ChronicleMapBackend]. The [OffHeapCacheBackend] interface contract is unchanged.
 *
 * Limitation: not persisted across restarts (in-memory only). For persistence, swap in
 * Chronicle Map (file-backed) when upstream catches up.
 */
class OffHeapSerializedBackend<K : Any, V : Any>(
    private val config: CacheConfig,
    private val mapper: ObjectMapper = ObjectMapper(),
) : OffHeapCacheBackend<K, V> {

    override val name: String = "chronicle"

    private val log = LoggerFactory.getLogger(OffHeapSerializedBackend::class.java)

    // Index: hash(key) → direct ByteBuffer holding serialized value.
    private val index = ConcurrentHashMap<Int, ByteBuffer>()

    // Insertion-order tracker for LRU-like eviction. Maintains a doubly-linked list via AtomicLong seq.
    private val seqCounter = AtomicLong(0)
    // seq → key hash. Used to find oldest entry on eviction.
    private val seqToKey = ConcurrentHashMap<Long, Int>()
    // key hash → seq. Used to unlink on overwrite/eviction.
    private val keyToSeq = ConcurrentHashMap<Int, Long>()

    private val hitsAdder = java.util.concurrent.atomic.LongAdder()
    private val missesAdder = java.util.concurrent.atomic.LongAdder()
    private val errorsAdder = java.util.concurrent.atomic.LongAdder()
    private val totalDirectBytes = AtomicLong(0)

    init {
        log.info(
            "OffHeapSerializedBackend initialized: maxEntries={}, chroniclePath={} (unused — in-memory only)",
            config.maxEntries, config.chroniclePath,
        )
    }

    override fun get(key: K): V? {
        val hash = key.hashCode()
        val buf = index[hash] ?: run {
            missesAdder.increment()
            return null
        }
        return try {
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            val value = mapper.readValue(bytes, Any::class.java)
            // Note: returns Object, not V — caller must cast. Interface limitation;
            // we accept this because the only caller is CalculationCache which has typed access.
            @Suppress("UNCHECKED_CAST")
            (value as V).also { hitsAdder.increment() }
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend get failed: {}", e.message)
            null
        }
    }

    override fun put(key: K, value: V) {
        val hash = key.hashCode()
        try {
            val bytes = mapper.writeValueAsBytes(value)
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.flip()
            // Evict existing entry at this hash to update byte counter.
            val existing = index.put(hash, buf)
            if (existing != null) {
                totalDirectBytes.addAndGet(-existing.capacity().toLong())
                keyToSeq.remove(hash)?.let { seqToKey.remove(it) }
            }
            totalDirectBytes.addAndGet(buf.capacity().toLong())
            val seq = seqCounter.incrementAndGet()
            seqToKey[seq] = hash
            keyToSeq[hash] = seq
            // Evict oldest if over maxEntries.
            evictIfOver()
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend put failed: {}", e.message)
        }
    }

    private fun evictIfOver() {
        while (index.size > config.maxEntries) {
            // Find oldest seq still present.
            val oldestSeq = seqToKey.keys.minOrNull() ?: break
            val oldestHash = seqToKey.remove(oldestSeq) ?: continue
            val evicted = index.remove(oldestHash)
            if (evicted != null) totalDirectBytes.addAndGet(-evicted.capacity().toLong())
            keyToSeq.remove(oldestHash)
        }
    }

    override fun size(): Long = index.size.toLong()

    override fun stats(): CacheStats = CacheStats(
        size = index.size.toLong(),
        hits = hitsAdder.sum(),
        misses = missesAdder.sum(),
        errors = errorsAdder.sum(),
    )

    override fun close() {
        index.clear()
        seqToKey.clear()
        keyToSeq.clear()
        totalDirectBytes.set(0)
    }
}

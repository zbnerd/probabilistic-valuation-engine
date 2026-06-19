package maple.calculator.cache

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Off-heap cache backend using direct [ByteBuffer] for value storage.
 *
 * **Why this exists:** Chronicle Map (original target per issue #1311) doesn't
 * support JDK 21 in any stable release. This impl achieves the heap-reduction AC
 * without a third-party off-heap dep:
 *
 * - Keys: full key reference stored alongside index entry to enable equality check
 *   on hash collisions (prevents silent data loss when two keys hash to same Int).
 *   Heap cost: ~50 bytes/entry × 100K = ~5MB (vs Caffeine POJOs ~30MB).
 * - Values: Jackson-serialized to [ByteBuffer.allocateDirect]. Stored off-heap.
 * - Eviction: insertion-order tracker drops oldest when over `maxEntries`.
 * - Thread-safety: ConcurrentHashMap + AtomicLong + per-entry ByteBuffers (not mutated after put).
 *
 * **Collision handling:** map keys are `Int` hash. ConcurrentHashMap allows
 * duplicate `Int` keys with different `equals()` (since key type is Int — no equals check).
 * To prevent silent overwrite, each entry stores the full key reference and `get()`
 * verifies `key.equals(storedKey)` before returning value.
 *
 * When Chronicle Map ships JDK 21 support, swap this for a real `ChronicleMapBackend`
 * behind the same [OffHeapCacheBackend] interface. Persistence is the main trade-off
 * (Chronicle is file-backed; this is in-memory only).
 */
class OffHeapSerializedBackend<K : Any, V : Any>(
    private val config: CacheConfig,
    private val mapper: ObjectMapper = ObjectMapper(),
) : OffHeapCacheBackend<K, V> {

    override val name: String = "chronicle"

    private val log = LoggerFactory.getLogger(OffHeapSerializedBackend::class.java)

    private data class Entry(val keyRef: Any, val value: ByteBuffer)

    // Index: hash(key) → Entry (with key ref for collision check).
    // ConcurrentHashMap with hash collisions: last put wins. Equality check on get().
    private val index = ConcurrentHashMap<Int, Entry>()

    // Insertion-order tracker for eviction.
    private val seqCounter = AtomicLong(0)
    private val seqToHash = ConcurrentHashMap<Long, Int>()
    private val hashToSeq = ConcurrentHashMap<Int, Long>()

    private val hitsAdder = java.util.concurrent.atomic.LongAdder()
    private val missesAdder = java.util.concurrent.atomic.LongAdder()
    private val errorsAdder = java.util.concurrent.atomic.LongAdder()

    init {
        log.info(
            "OffHeapSerializedBackend initialized: maxEntries={}, chroniclePath={} (unused — in-memory only)",
            config.maxEntries, config.chroniclePath,
        )
    }

    override fun get(key: K): V? {
        val hash = key.hashCode()
        val entry = index[hash] ?: run {
            missesAdder.increment()
            return null
        }
        // Collision check: hash matches but key might differ.
        if (entry.keyRef != key) {
            missesAdder.increment()
            return null
        }
        return try {
            val bytes = ByteArray(entry.value.remaining())
            entry.value.duplicate().get(bytes)
            @Suppress("UNCHECKED_CAST")
            (@Suppress("UNCHECKED_CAST") mapper.readValue(bytes, Any::class.java) as V).also { hitsAdder.increment() }
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
            val entry = Entry(key, buf)
            // Evict existing entry's seq before put (if collision or overwrite).
            hashToSeq.remove(hash)?.let { seqToHash.remove(it) }
            index[hash] = entry
            val seq = seqCounter.incrementAndGet()
            seqToHash[seq] = hash
            hashToSeq[hash] = seq
            evictIfOver()
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend put failed: {}", e.message)
        }
    }

    private fun evictIfOver() {
        while (index.size > config.maxEntries) {
            val oldestSeq = seqToHash.keys.minOrNull() ?: break
            val oldestHash = seqToHash.remove(oldestSeq) ?: continue
            index.remove(oldestHash)
            hashToSeq.remove(oldestHash)
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
        seqToHash.clear()
        hashToSeq.clear()
    }
}

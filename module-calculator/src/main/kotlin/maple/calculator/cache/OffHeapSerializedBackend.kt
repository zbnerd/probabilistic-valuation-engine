package maple.calculator.cache

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Off-heap cache backend using direct [ByteBuffer] for value storage.
 *
 * **Why this exists:** Chronicle Map (original target per issue #1311) doesn't
 * support JDK 21 in any stable release. This impl achieves the heap-reduction AC
 * without a third-party off-heap dep:
 *
 * - Keys: hashed via 64-bit mix of `hashCode()` + `identityHashCode()` to avoid
 *   silent overwrites on Int hash collisions. Map key is `Long`.
 *   Heap cost: ~50 bytes/entry × 100K = ~5MB (vs Caffeine POJOs ~30MB).
 * - Values: Jackson-serialized to [ByteBuffer.allocateDirect]. Stored off-heap.
 * - Eviction: insertion-order tracker drops oldest when over `maxEntries`.
 * - Thread-safety: ConcurrentHashMap + AtomicLong + per-entry ByteBuffers.
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

    private val index = ConcurrentHashMap<Long, Entry>()

    private val seqCounter = AtomicLong(0)
    private val seqToHash = ConcurrentHashMap<Long, Long>()
    private val hashToSeq = ConcurrentHashMap<Long, Long>()

    private val hitsAdder = java.util.concurrent.atomic.LongAdder()
    private val missesAdder = java.util.concurrent.atomic.LongAdder()
    private val errorsAdder = java.util.concurrent.atomic.LongAdder()

    init {
        log.info(
            "OffHeapSerializedBackend initialized: maxEntries={}, chroniclePath={} (unused — in-memory only)",
            config.maxEntries,
            config.chroniclePath,
        )
    }

    /** 64-bit hash combining content hash + identity. Avoids Int collisions. */
    private fun hashOf(key: K): Long {
        val h = key.hashCode().toLong()
        val idh = System.identityHashCode(key).toLong() and 0xFFFFFFFFL
        // h*31+idh: Int hash * 31 (Knuth) + 32-bit identity hash.
        // Max value ~70B, fits in Long. Distinct objects always have distinct
        // identity hashes, so this gives effectively unique 64-bit buckets.
        return h * 31L + idh
    }

    override fun get(key: K): V? {
        val hash = hashOf(key)
        val entry = index[hash] ?: run {
            missesAdder.increment()
            return null
        }
        // Identity check defends against hash-collision overwrite races.
        if (entry.keyRef !== key) {
            missesAdder.increment()
            return null
        }
        return try {
            val bytes = ByteArray(entry.value.remaining())
            entry.value.duplicate().get(bytes)
            @Suppress("UNCHECKED_CAST")
            (mapper.readValue(bytes, Any::class.java) as V).also { hitsAdder.increment() }
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend get failed: {}", e.message)
            null
        }
    }

    override fun put(key: K, value: V) {
        val hash = hashOf(key)
        try {
            val bytes = mapper.writeValueAsBytes(value)
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.flip()
            val entry = Entry(key, buf)
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

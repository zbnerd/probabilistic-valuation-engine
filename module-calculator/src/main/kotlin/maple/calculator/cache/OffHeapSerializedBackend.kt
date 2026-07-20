package maple.calculator.cache

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.ByteBuffer
import java.security.MessageDigest
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
 * - Keys: canonical Jackson bytes keyed by the first 128 bits of SHA-256, with
 *   the full bytes retained and compared to defend against digest collisions.
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
    mapper: ObjectMapper,
    keyClass: Class<K>,
    private val valueClass: Class<V>,
) : OffHeapCacheBackend<K, V> {

    override val name: String = "chronicle"

    private val log = LoggerFactory.getLogger(OffHeapSerializedBackend::class.java)

    private class CanonicalKey(
        private val digestHigh: Long,
        private val digestLow: Long,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is CanonicalKey &&
            digestHigh == other.digestHigh &&
            digestLow == other.digestLow &&
            bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * digestHigh.hashCode() + digestLow.hashCode()
    }

    private data class Entry(
        val canonicalKeyBytes: ByteArray,
        val value: ByteBuffer,
    )

    private val canonicalMapper = mapper.copy()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    private val keyWriter = canonicalMapper.writerFor(keyClass)
    private val valueMapper = mapper
    private val index = ConcurrentHashMap<CanonicalKey, Entry>()

    private val seqCounter = AtomicLong(0)
    private val seqToKey = ConcurrentHashMap<Long, CanonicalKey>()
    private val keyToSeq = ConcurrentHashMap<CanonicalKey, Long>()

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

    private fun canonicalKey(key: K): CanonicalKey {
        val bytes = keyWriter.writeValueAsBytes(key)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digestBuffer = ByteBuffer.wrap(digest)
        return CanonicalKey(
            digestHigh = digestBuffer.long,
            digestLow = digestBuffer.long,
            bytes = bytes,
        )
    }

    override fun get(key: K): V? {
        val canonicalKey = runCatching { canonicalKey(key) }.getOrElse { failure ->
            if (failure is Error) throw failure
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend key serialization failed: {}", failure.message)
            return null
        }
        val entry = index[canonicalKey] ?: run {
            missesAdder.increment()
            return null
        }
        if (!entry.canonicalKeyBytes.contentEquals(canonicalKey.bytes)) {
            missesAdder.increment()
            return null
        }
        return runCatching {
            val bytes = ByteArray(entry.value.remaining())
            entry.value.duplicate().get(bytes)
            valueMapper.readValue(bytes, valueClass)
        }.onSuccess {
            hitsAdder.increment()
        }.getOrElse { failure ->
            if (failure is Error) throw failure
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend get failed: {}", failure.message)
            null
        }
    }

    override fun put(key: K, value: V) {
        runCatching {
            val canonicalKey = canonicalKey(key)
            val bytes = valueMapper.writeValueAsBytes(value)
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.flip()
            val entry = Entry(canonicalKey.bytes, buf)
            keyToSeq.remove(canonicalKey)?.let { seqToKey.remove(it) }
            index[canonicalKey] = entry
            val seq = seqCounter.incrementAndGet()
            seqToKey[seq] = canonicalKey
            keyToSeq[canonicalKey] = seq
            evictIfOver()
        }.onFailure { failure ->
            if (failure is Error) throw failure
            errorsAdder.increment()
            log.error("OffHeapSerializedBackend put failed: {}", failure.message)
        }
    }

    private fun evictIfOver() {
        while (index.size.toLong() > config.maxEntries) {
            val oldestSeq = seqToKey.keys.minOrNull() ?: break
            val oldestKey = seqToKey.remove(oldestSeq) ?: continue
            index.remove(oldestKey)
            keyToSeq.remove(oldestKey)
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
    }
}

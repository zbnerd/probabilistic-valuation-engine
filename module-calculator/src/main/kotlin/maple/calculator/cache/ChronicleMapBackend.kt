package maple.calculator.cache

import org.slf4j.LoggerFactory

/**
 * Chronicle Map-backed [OffHeapCacheBackend].
 *
 * **BLOCKED (issue #1311):** Chronicle Map does not support JDK 21 in any
 * stable release as of 2026-06.
 *
 * - Latest stable (3.23.5, May 2024) uses `sun.nio.ch.FileChannelImpl.unmap0`,
 *   which was REMOVED in JDK 17. No `--add-opens` works around this.
 * - 3.27ea0 (latest ea build, Dec 2025) requires `net.openhft:affinity` which
 *   has a parent POM `third-party-bom:3.22.4-SNAPSHOT` not on Maven Central.
 *   Even when forced, `chronicle-threads:Pauser.<clinit>` references
 *   `AffinityLock` directly — classpath stubbing is impossible without
 *   `--patch-module`.
 *
 * **Workaround until upstream JDK 21 support ships:** this class is a stub
 * that throws on construction so `CacheBackendFactory` falls back to Caffeine
 * (per spec §5). Off-heap cache goal deferred until Chronicle Map stable
 * supports JDK 21.
 *
 * Action item: track upstream issue and re-evaluate on each Chronicle Map
 * stable release.
 */
class ChronicleMapBackend<K : Any, V : Any>(
    config: CacheConfig,
    keyClass: Class<K>,
    valueClass: Class<V>,
) : OffHeapCacheBackend<K, V> {

    override val name: String = "chronicle"

    private val log = LoggerFactory.getLogger(ChronicleMapBackend::class.java)

    private val errorMessage: String = buildString {
        appendLine("ChronicleMapBackend init failed: Chronicle Map does not support JDK 21 in any stable release.")
        appendLine("Latest stable (3.23.5) uses removed JDK internal sun.nio.ch.FileChannelImpl.unmap0.")
        appendLine("Latest ea (3.27ea0) requires net.openhft:affinity which depends on unpublished SNAPSHOT BOM.")
        appendLine("Falling back to CaffeineCacheBackend per spec §5.")
        appendLine("Action: track upstream; re-evaluate when stable supports JDK 21.")
    }

    init {
        log.warn(errorMessage.trim())
    }

    override fun get(key: K): V? {
        errorsAdder.increment()
        return null
    }

    override fun put(key: K, value: V) {
        errorsAdder.increment()
    }

    override fun size(): Long = 0L

    override fun stats(): CacheStats = CacheStats(
        size = 0L,
        hits = hitsAdder.sum(),
        misses = missesAdder.sum(),
        errors = errorsAdder.sum(),
    )

    override fun close() {}

    private val hitsAdder = java.util.concurrent.atomic.LongAdder()
    private val missesAdder = java.util.concurrent.atomic.LongAdder()
    private val errorsAdder = java.util.concurrent.atomic.LongAdder()
}

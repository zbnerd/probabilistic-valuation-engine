package maple.calculator.cache

import org.slf4j.LoggerFactory

/**
 * Selects and instantiates the configured [OffHeapCacheBackend].
 *
 * Profile values:
 * - "caffeine" (default): instantiates CaffeineCacheBackend directly.
 * - "chronicle": tries OffHeapSerializedBackend; falls back to CaffeineCacheBackend
 *   on recoverable init failure (Exception, NoClassDefFoundError, LinkageError),
 *   logging WARN. Auto-fallback is per spec §5.
 * - anything else: logs ERROR, returns CaffeineCacheBackend.
 *
 * Why multi-catch (not `catch (Throwable)`): `OutOfMemoryError`, `ThreadDeath`,
 * `StackOverflowError` are NOT recoverable. Explicit list prevents swallowing
 * JVM-fatal errors.
 *
 * **Chronicle Map note (issue #1311):** the original target was Chronicle Map,
 * but no stable release supports JDK 21 (uses removed sun.nio.ch.FileChannelImpl.unmap0).
 * `OffHeapSerializedBackend` is the actual off-heap impl (direct ByteBuffer + Jackson).
 * When Chronicle ships JDK 21 support, swap the impl behind the same factory.
 */
object CacheBackendFactory {

    private val log = LoggerFactory.getLogger(CacheBackendFactory::class.java)

    fun <K : Any, V : Any> create(
        profile: String,
        config: CacheConfig,
        keyClass: Class<K>,
        valueClass: Class<V>,
    ): OffHeapCacheBackend<K, V> = when (profile.lowercase()) {
        "caffeine" -> CaffeineCacheBackend(config)

        "chronicle" -> try {
            @Suppress("UNCHECKED_CAST")
            OffHeapSerializedBackend<K, V>(config)
        } catch (e: Exception) {
            fallbackToCaffeine(profile, config, e)
        } catch (e: NoClassDefFoundError) {
            fallbackToCaffeine(profile, config, e)
        } catch (e: LinkageError) {
            fallbackToCaffeine(profile, config, e)
        }

        else -> {
            log.error("Unknown calculator.cache.backend='{}'; defaulting to caffeine", profile)
            CaffeineCacheBackend(config)
        }
    }

    private fun <K : Any, V : Any> fallbackToCaffeine(
        profile: String,
        config: CacheConfig,
        cause: Throwable,
    ): OffHeapCacheBackend<K, V> {
        val msg = cause.message ?: cause.javaClass.simpleName
        log.warn(
            "OffHeapSerializedBackend init failed for profile='{}' ({}: {}); falling back to CaffeineCacheBackend",
            profile,
            cause.javaClass.simpleName,
            msg,
        )
        return CaffeineCacheBackend(config)
    }
}

package maple.calculator.cache

/**
 * Cache backend configuration. Bound from `calculator.cache.*` YAML keys.
 *
 * @property maxEntries Caffeine maximumSize / Chronicle entries().
 *   Sized to 100K to match existing working set (one chunk of item-equipment lookups).
 * @property chroniclePath Filesystem path for the Chronicle Map mmap file.
 *   Ignored when backend is caffeine.
 */
data class CacheConfig(
    val maxEntries: Long = 100_000L,
    val chroniclePath: String = "/var/lib/calculator/chronicle-ocid",
)

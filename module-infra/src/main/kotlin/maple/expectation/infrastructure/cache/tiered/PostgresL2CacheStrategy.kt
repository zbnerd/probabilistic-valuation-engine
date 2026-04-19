package maple.expectation.infrastructure.cache.tiered

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Instant
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PostgreSQL L2 Cache Strategy Implementation (Issue #247)
 *
 * <h3>Purpose</h3>
 *
 * <p>Provides L2 cache backend using PostgreSQL as storage. Replaces Redis for
 * cache persistence while maintaining the same L2CacheStrategy interface.
 *
 * <h3>Architecture</h3>
 *
 * <ul>
 *   <li><strong>Storage:</strong> PostgreSQL `cache_storage` table with key-value schema</li>
 *   <li><strong>Serialization:</strong> Jackson ObjectMapper for JSON serialization to BYTEA</li>
 *   <li><strong>TTL Support:</strong> expires_at column with automatic expiration filtering</li>
 *   <li><strong>Key Format:</strong> `{cacheName}:v1:{actualKey}` for namespacing</li>
 * </ul>
 *
 * <h3>Design Patterns</h3>
 *
 * <ul>
 *   <li><strong>Strategy Pattern:</strong> Implements L2CacheStrategy interface</li>
 *   <li><strong>Graceful Degradation:</strong> Returns null on errors, never throws to callers</li>
 *   <li><strong>Zero Try-Catch:</strong> All operations wrapped in LogicExecutor (Section 12)</li>
 * </ul>
 *
 * <h3>Performance Characteristics</h3>
 *
 * <ul>
 *   <li>Read latency: ~2-5ms (indexed key lookup)</li>
 *   <li>Write latency: ~3-8ms (UPSERT with TTL)</li>
 *   <li>Evict latency: ~1-3ms (indexed key delete)</li>
 *   <li>EvictAll latency: ~5-15ms (partial index scan)</li>
 * </ul>
 *
 * @property jdbcTemplate Spring JDBC template for database operations
 * @property executor LogicExecutor for error handling and logging
 * @property objectMapper Jackson object mapper for serialization
 * @property meterRegistry Micrometer registry for metrics
 * @see L2CacheStrategy
 * @see PostgresL2CacheFactory
 */
@Component
class PostgresL2CacheStrategy(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val cacheProperties: CacheProperties,
) : L2CacheStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresL2CacheStrategy::class.java)
        private const val DEFAULT_KEY_VERSION = "v1"

        /**
         * ThreadLocal flag to disable L2 cache writes during bulk loading.
         * When true, put() operations are skipped to avoid Postgres write overhead.
         *
         * IMPORTANT: Must be cleaned up after use via [remove()] to prevent
         * thread pool pollution in Tomcat/Virtual Thread environments.
         */
        internal val disableL2Writes: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        /**
         * Execute a block with L2 writes disabled, ensuring ThreadLocal cleanup.
         */
        fun <T> withL2WritesDisabled(block: () -> T): T {
            disableL2Writes.set(true)
            try {
                return block()
            } finally {
                disableL2Writes.remove()
            }
        }
    }

    // Metrics
    private val getCounter: Counter = meterRegistry.counter("cache.l2.strategy.get", "impl", "postgres")
    private val getAllCounter: Counter = meterRegistry.counter("cache.l2.strategy.getall", "impl", "postgres")
    private val putCounter: Counter = meterRegistry.counter("cache.l2.strategy.put", "impl", "postgres")
    private val evictCounter: Counter = meterRegistry.counter("cache.l2.strategy.evict", "impl", "postgres")
    private val evictAllCounter: Counter = meterRegistry.counter("cache.l2.strategy.evictall", "impl", "postgres")
    private val errorCounter: Counter = meterRegistry.counter("cache.l2.strategy.error", "impl", "postgres")

    override fun <T : Any> get(key: String, type: Class<T>): T? {
        val context = TaskContext.of("PostgresL2Strategy", "Get", key)

        return executor.executeOrDefault(
            {
                getCounter.increment()

                val sql = """
                    SELECT cache_value
                    FROM cache_storage
                    WHERE cache_key = ?
                      AND expires_at > NOW()
                """.trimIndent()

                // Use query() instead of queryForObject() to avoid EmptyResultDataAccessException
                // being logged as ERROR by executor on normal cache misses
                val results = jdbcTemplate.query(
                    sql,
                    { rs, _ -> rs.getBytes("cache_value") },
                    key,
                )

                val result = results.firstOrNull()

                result?.let { bytes ->
                    try {
                        val typedValue = objectMapper.readValue(bytes, TypedValue::class.java)
                        @Suppress("UNCHECKED_CAST")
                        when {
                            type == String::class.java || type == Any::class.java -> typedValue.value as? T
                            typedValue.value != null && type.isInstance(typedValue.value) -> typedValue.value as T
                            else -> null
                        }
                    } catch (e: Exception) {
                        log.error("[PostgresL2] Deserialization failed for key={}", key, e)
                        errorCounter.increment()
                        null
                    }
                }
            },
            null,
            context,
        ).also { error ->
            if (error != null) {
                errorCounter.increment()
            }
        }
    }

    /**
     * Batch retrieval from L2 cache using IN query
     *
     * <p>More efficient than individual gets when retrieving multiple keys.
     */
    override fun <T : Any> getAll(keys: List<String>, type: Class<T>): Map<String, T> {
        if (keys.isEmpty()) return emptyMap()

        val context = TaskContext.of("PostgresL2Strategy", "GetAll", "${keys.size}")

        return executor.executeOrDefault(
            {
                getAllCounter.increment()

                val placeholders = keys.map { "?" }.joinToString(",")
                val sql = """
                    SELECT cache_key, cache_value
                    FROM cache_storage
                    WHERE cache_key IN ($placeholders)
                      AND expires_at > NOW()
                """.trimIndent()

                jdbcTemplate.query(
                    sql,
                    { rs, _ ->
                        val key = rs.getString("cache_key")
                        val bytes = rs.getBytes("cache_value")
                        val typedValue = objectMapper.readValue(bytes, TypedValue::class.java)
                        // Apply same type safety as get() method
                        @Suppress("UNCHECKED_CAST")
                        val value: T? = when {
                            type == String::class.java || type == Any::class.java -> typedValue.value as? T
                            typedValue.value != null && type.isInstance(typedValue.value) -> typedValue.value as T
                            else -> null
                        }
                        if (value != null) key to value else null
                    },
                    *keys.toTypedArray(),
                ).filterNotNull().associate { it }
            },
            emptyMap(),
            context,
        ).also { error ->
            if (error.isNotEmpty()) {
                errorCounter.increment()
            }
        }
    }

    override fun put(key: String, value: Any, ttlMinutes: Long) {
        // Skip L2 writes if disabled (e.g., during bulk loading)
        if (disableL2Writes.get() == true) {
            log.debug("[PostgresL2] Skipping L2 write (disabled): key={}", key)
            return
        }

        val context = TaskContext.of("PostgresL2Strategy", "Put", key)

        executor.executeVoidJava(
            {
                putCounter.increment()

                // Wrap value in TypedValue to preserve type information during deserialization
                val typedValue = TypedValue(value)
                val valueBytes: ByteArray = objectMapper.writeValueAsBytes(typedValue)

                // Calculate expiration timestamp
                val expiresAt = Timestamp.from(
                    Instant.now().plusSeconds(ttlMinutes * 60),
                )

                val sql = """
                    INSERT INTO cache_storage (cache_key, cache_value, expires_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (cache_key)
                    DO UPDATE SET
                        cache_value = EXCLUDED.cache_value,
                        expires_at = EXCLUDED.expires_at
                """.trimIndent()

                jdbcTemplate.update(
                    sql,
                    key,
                    valueBytes,
                    expiresAt,
                )

                log.debug("[PostgresL2] Put: key={}, ttl={}min", key, ttlMinutes)
            },
            context,
        )
    }

    override fun evict(key: String) {
        val context = TaskContext.of("PostgresL2Strategy", "Evict", key)

        executor.executeVoidJava(
            {
                evictCounter.increment()

                val sql = "DELETE FROM cache_storage WHERE cache_key = ?"

                val deleted = jdbcTemplate.update(sql, key)

                log.debug("[PostgresL2] Evict: key={}, deleted={}", key, deleted)
            },
            context,
        )
    }

    override fun evictAll(cacheName: String) {
        val context = TaskContext.of("PostgresL2Strategy", "EvictAll", cacheName)

        executor.executeVoidJava(
            {
                evictAllCounter.increment()

                // Key format: {cacheName}:v1:{actualKey}
                // Range query using >= and < with COLLATE "C" for B-tree index scan
                // '~' (0x7E) is the highest printable ASCII char, guaranteed > any valid actualKey
                val keyPrefix = "$cacheName:${cacheProperties.keyVersion}:"
                val upperBound = "$cacheName:${cacheProperties.keyVersion}~"
                val sql = "DELETE FROM cache_storage WHERE cache_key >= ? COLLATE \"C\" AND cache_key < ? COLLATE \"C\""

                val deleted = jdbcTemplate.update(sql, keyPrefix, upperBound)

                log.debug("[PostgresL2] EvictAll: cacheName={}, deleted={}", cacheName, deleted)
            },
            context,
        )
    }

    override fun isHealthy(): Boolean {
        val context = TaskContext.of("PostgresL2Strategy", "HealthCheck")

        return executor.executeOrDefault(
            {
                try {
                    // Lightweight health check: query pg_stat_activity
                    val sql = "SELECT 1"
                    jdbcTemplate.queryForObject(sql, Int::class.java) == 1
                } catch (e: Exception) {
                    log.error("[PostgresL2] Health check failed", e)
                    false
                }
            },
            false,
            context,
        )
    }

    /**
     * Generate cache key with namespace and version
     *
     * <h4>Key Format</h4>
     *
     * <pre>
     * {cacheName}:v1:{actualKey}
     * </pre>
     *
     * <h4>Example</h4>
     *
     * <pre>
     * "equipment_cache:v1:character:123"
     * </pre>
     *
     * @param cacheName Cache name (e.g., "equipment_cache")
     * @param actualKey Actual cache key (e.g., "character:123")
     * @return Full cache key with namespace
     */
    fun generateKey(cacheName: String, actualKey: String): String {
        require('~' !in cacheName && '~' !in actualKey) {
            "Cache key must not contain '~' (reserved for range query boundary): cacheName=$cacheName, actualKey=$actualKey"
        }
        return "$cacheName:${cacheProperties.keyVersion}:$actualKey"
    }
}

package maple.restcontroller.read

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class ReadModelQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val documentExtractor: ReadModelDocumentExtractor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param requests userIgn -> presetNo mapping
     * @return userIgn -> V6ExpectationResponse for hits only
     *
     * Issue #1130: CompletableFuture 반환. JDBC IO on Dispatchers.IO (asExecutor).
     * CPU (gzip+JSON parse via documentExtractor.extract) on Dispatchers.Default.
     */
    fun batchQuery(
        requests: Map<String, Int>,
        maxAge: Duration? = null,
    ): CompletableFuture<Map<String, V6ExpectationResponse>> {
        if (requests.isEmpty()) return CompletableFuture.completedFuture(emptyMap())

        val built = ReadModelRowQuery.build(requests)
        val minimumUpdatedAt = maxAge?.let { Instant.now().minus(it) }

        return CompletableFuture.supplyAsync({
            // IO: JDBC query
            val raw = jdbc.queryForList(built.first, built.second)
            StalenessCheck.partitionStale(raw, minimumUpdatedAt)
        }, Dispatchers.IO.asExecutor()).thenApplyAsync({ partitioned ->
            // CPU: gzip decompress + JSON parse (documentExtractor.extract per row) on Default
            val fresh = partitioned.first
            val stale = partitioned.second
            val result = LinkedHashMap<String, V6ExpectationResponse>(fresh.size)
            fresh.forEach { row ->
                val userIgn = row["user_ign"].toString()
                result[userIgn] = documentExtractor.extract(userIgn, row["document"] as ByteArray, row)
            }
            log.debug("Read model query: requested={}, hits={}, stale={}", requests.size, result.size, stale)
            result
        }, Dispatchers.Default.asExecutor())
    }
}

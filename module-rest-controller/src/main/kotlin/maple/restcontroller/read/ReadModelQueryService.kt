package maple.restcontroller.read

import java.time.Duration
import java.time.Instant
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
     */
    fun batchQuery(
        requests: Map<String, Int>,
        maxAge: Duration? = null,
    ): Map<String, V6ExpectationResponse> {
        if (requests.isEmpty()) return emptyMap()

        val built = ReadModelRowQuery.build(requests)
        val raw = jdbc.queryForList(built.first, built.second)
        val minimumUpdatedAt = maxAge?.let { Instant.now().minus(it) }
        val partitioned = StalenessCheck.partitionStale(raw, minimumUpdatedAt)
        val fresh = partitioned.first
        val stale = partitioned.second

        val result = LinkedHashMap<String, V6ExpectationResponse>(fresh.size)
        fresh.forEach { row ->
            val userIgn = row["user_ign"].toString()
            result[userIgn] = documentExtractor.extract(userIgn, row["document"] as ByteArray, row)
        }
        log.debug("Read model query: requested={}, hits={}, stale={}", requests.size, result.size, stale)
        return result
    }
}

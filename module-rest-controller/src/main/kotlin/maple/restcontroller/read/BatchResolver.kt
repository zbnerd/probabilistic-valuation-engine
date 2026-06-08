package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.metrics.V6ReadMetrics
import maple.restcontroller.urgent.UrgentCharacterRequest
import maple.restcontroller.urgent.UrgentTriggerPublisher
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Resolves a batch of read requests against Redis cache and DB.
 *
 * Returns a typed [BatchResolveResult] describing which requests were answered
 * (cache hit / DB hit / negative cache hit) and which were left pending (urgent
 * pipeline triggered or no urgent publisher). The caller is responsible for
 * mapping each [ResolvedItem] to a `ResponseEntity` and applying it to the
 * matching deferreds — see [ExpectationReadResponseMapper].
 */
class BatchResolver(
    private val readModelCacheService: ReadModelCacheService,
    private val negativeCacheService: NegativeCacheService,
    private val urgentDedupService: UrgentDedupService,
    private val queryService: ReadModelQueryService,
    private val urgentPublisher: UrgentTriggerPublisher?,
    private val properties: V6ReadProperties,
    private val metrics: V6ReadMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun resolveBatch(batch: List<ReadRequest>): BatchResolveResult {
        if (batch.isEmpty()) return BatchResolveResult.AllResolved(emptyList())

        val requests = batch.associate { it.userIgn to it.presetNo }
        val resolved = mutableListOf<ResolvedItem>()

        // 1. Redis cache lookup — split hits / misses
        val (cacheHits, cacheMisses) = readModelCacheService.multiGet(requests)

        // 2. Resolve cache hits
        cacheHits.forEach { (userIgn, response) ->
            metrics.recordHit()
            metrics.recordRedisHit()
            resolved += ResolvedItem.Ok(
                userIgn = userIgn,
                presetNo = response.presetNo,
                response = response,
            )
        }

        // 3. DB batch query for cache misses, including urgent-pending keys.
        if (cacheMisses.isNotEmpty()) {
            val dbResults = queryService.batchQuery(
                cacheMisses,
                Duration.ofSeconds(properties.readModelFreshnessSeconds),
            )

            // 4. Write DB results to Redis cache
            readModelCacheService.multiPut(dbResults)

            // 5. Resolve miss deferreds
            cacheMisses.keys.forEach { userIgn ->
                val presetNo = cacheMisses[userIgn] ?: 1
                val response = dbResults[userIgn]

                if (response != null) {
                    metrics.recordHit()
                    metrics.recordDbHit()
                    resolved += ResolvedItem.Ok(
                        userIgn = userIgn,
                        presetNo = presetNo,
                        response = response,
                    )
                } else {
                    metrics.recordMiss("read_model_empty")

                    // Check negative cache first (character previously confirmed not found by Nexon)
                    if (negativeCacheService.getNegativeCache(userIgn)) {
                        resolved += ResolvedItem.NotFound(
                            userIgn = userIgn,
                            presetNo = presetNo,
                        )
                        return@forEach
                    }

                    // Trigger urgent pipeline (with dedup via Redis SETNX)
                    if (urgentPublisher != null && urgentDedupService.tryMarkUrgentPending(userIgn)) {
                        urgentPublisher.publish(UrgentCharacterRequest(userIgn = userIgn, presetNo = presetNo))
                        metrics.urgentTriggerTotal.increment()
                        log.info("Triggered urgent pipeline: userIgn={}", maskIgn(userIgn))
                    }

                    // Otherwise DeferredResult will time out → 202 Accepted
                }
            }
        }

        val pendingCount = batch.size - resolved.size
        return if (pendingCount == 0) {
            BatchResolveResult.AllResolved(resolved)
        } else {
            BatchResolveResult.PartiallyResolved(resolved, pendingCount)
        }
    }
}

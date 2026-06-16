package maple.calculator.runstate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import maple.calculator.config.ExternalApiRunStatusProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Tracks the runId that the external-api currently considers "active" by
 * polling its /api/internal/run-status endpoint.
 *
 * The calculator's [maple.calculator.CalculatorChunkProcessingCoordinator]
 * consults [isKnownRunId] when deciding whether to process a chunk-ready
 * event: if the event's runId is not known, the chunk is dropped as stale
 * (and counted via `calculator_chunks_skipped_total{reason=stale_run}`).
 *
 * Two runId categories are "known":
 * - The daily runId polled from ext-api's `/api/internal/run-status`.
 * - Per-cycle runIds produced by ext-api's `ItemEquipmentContinuousLoop`,
 *   registered via [discoverCycleRunId] when the coordinator first observes
 *   a chunk-ready event whose object key actually exists in storage.
 *
 * Polling interval is 30s. A null or stale poll (older than [maxFreshSeconds])
 * is treated as "unknown" — the coordinator falls back to processing (better
 * to risk a stale chunk than to lose new work).
 *
 * Discovered cycle runIds are bounded by [MAX_DISCOVERED_RUN_IDS] to prevent
 * unbounded growth from misbehaving producers.
 */
@Component
class CalculatorCurrentRunIdHolder(
    private val properties: ExternalApiRunStatusProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CalculatorCurrentRunIdHolder::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val snapshot = AtomicReference<Snapshot?>(null)
    private val knownRunIds = ConcurrentHashMap.newKeySet<String>()

    fun currentRunIdOrNull(): String? = snapshot.get()?.runId

    /**
     * True if [runId] matches the daily runId OR was previously discovered
     * as a cycle runId via [discoverCycleRunId]. Urgent-path runIds
     * (`urgent-*` prefix) are NOT tracked here — they bypass the check
     * in the coordinator.
     */
    fun isKnownRunId(runId: String): Boolean {
        if (snapshot.get()?.runId == runId) return true
        return knownRunIds.contains(runId)
    }

    /**
     * Register a runId discovered via successful MinIO existence check. Called
     * by the coordinator when a non-matching event's chunk actually exists in
     * storage — typically the per-cycle runId emitted by
     * `ItemEquipmentContinuousLoop`. Subsequent events with this runId skip
     * the existence check and proceed to processing.
     */
    fun discoverCycleRunId(runId: String) {
        if (runId.startsWith("urgent-")) return
        if (knownRunIds.size >= MAX_DISCOVERED_RUN_IDS) {
            // Defensive cap. A real run emits O(10) cycles; this is far above that.
            log.warn("[CurrentRunId] discovered-runIds at cap, ignoring: runId={}", runId)
            return
        }
        if (knownRunIds.add(runId)) {
            log.info("[CurrentRunId] discovered cycle runId: {}", runId)
        }
    }

    fun isStale(): Boolean {
        val s = snapshot.get() ?: return true
        return Duration.between(s.polledAt, Instant.now()) > Duration.ofSeconds(maxFreshSeconds)
    }

    @Scheduled(fixedRateString = "\${calculator.run-id-tracker.poll-interval-ms:30000}")
    fun poll() {
        val url = properties.baseUrl.trimEnd('/') + "/api/internal/run-status"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        try {
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("[CurrentRunId] poll non-200: status={} url={}", res.statusCode(), url)
                return
            }
            val tree: JsonNode = objectMapper.readTree(res.body())
            val runId = tree.path("current").path("runId").asText(null)
            if (runId.isNullOrBlank() || runId == "N/A") {
                return
            }
            val prev = snapshot.get()?.runId
            snapshot.set(Snapshot(runId, Instant.now()))
            if (prev != runId) {
                log.info("[CurrentRunId] runId changed: prev={} new={}", prev, runId)
            }
        } catch (ex: Exception) {
            log.warn("[CurrentRunId] poll failed: url={} err={}", url, ex.message)
        }
    }

    private data class Snapshot(val runId: String, val polledAt: Instant)

    companion object {
        private const val maxFreshSeconds = 120L
        private const val MAX_DISCOVERED_RUN_IDS = 64
    }
}

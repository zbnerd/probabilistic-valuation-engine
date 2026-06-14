package maple.calculator.runstate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
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
 * consults [currentRunIdOrNull] when deciding whether to process a
 * chunk-ready event: if the event's runId does not match, the chunk is
 * dropped as stale (and counted via `calculator_chunks_skipped_total{reason=stale_run}`).
 *
 * Polling interval is 30s. A null or stale poll (older than [maxFreshSeconds])
 * is treated as "unknown" — the coordinator falls back to processing (better
 * to risk a stale chunk than to lose new work).
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

    fun currentRunIdOrNull(): String? = snapshot.get()?.runId

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
    }
}

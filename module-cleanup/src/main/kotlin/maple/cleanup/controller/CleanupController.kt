package maple.cleanup.controller

import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.cleanup.service.StaleKafkaSkipService
import maple.common.cleanup.RunCleanupResult
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP endpoints for Airflow-triggered cleanup.
 *
 * - POST /api/internal/cleanup/runs            → whole-run GC for runs/
 * - POST /api/internal/cleanup/calculator-runs  → whole-run GC for calculator/runs/
 * - POST /api/internal/cleanup/inbox           → drain event queue + delete per event
 *
 * Inbox deletion uses ObjectStorage (object keys are absolute storage keys, not
 * filesystem paths). InboxProperties.basePath is retained for backward compat
 * with existing YAML, but is no longer used for deletion.
 */
@RestController
@RequestMapping("/api/internal/cleanup")
class CleanupController(
    private val runCleanupService: RunCleanupService,
    private val inbox: ConsumedChunkInbox,
    private val inboxProperties: InboxProperties,
    private val objectStorage: ObjectStorage,
    private val staleKafkaSkipService: StaleKafkaSkipService,
) {
    private val log = LoggerFactory.getLogger(CleanupController::class.java)

    @PostMapping("/runs")
    fun cleanupRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /runs")
        return ResponseEntity.ok(runCleanupService.cleanupRuns())
    }

    @PostMapping("/stale-kafka")
    fun scanStaleKafka(
        @org.springframework.web.bind.annotation.RequestBody request: StaleKafkaRequest,
    ): ResponseEntity<List<StaleKafkaSkipService.ScanResult>> {
        log.info("[CleanupController] POST /stale-kafka topics={} keepRunIds={}", request.topics, request.keepRunIds)
        val results = request.topics.map { topic ->
            staleKafkaSkipService.scanForStaleMessages(
                topic = topic,
                consumerGroup = request.consumerGroup,
                keepRunIds = request.keepRunIds.toSet(),
            )
        }
        return ResponseEntity.ok(results)
    }

    data class StaleKafkaRequest(
        val topics: List<String>,
        val consumerGroup: String,
        val keepRunIds: List<String>,
    )

    @PostMapping("/calculator-runs")
    fun cleanupCalculatorRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /calculator-runs")
        return ResponseEntity.ok(runCleanupService.cleanupCalculatorRuns())
    }

    @PostMapping("/inbox")
    fun cleanupInbox(): ResponseEntity<InboxCleanupResponse> {
        log.info("[CleanupController] POST /inbox, size={}", inbox.size())
        val events = inbox.drain()
        var deleted = 0
        var failed = 0
        events.forEach { event ->
            if (deleteObject(event.objectKey)) deleted++ else failed++
            event.sourceObjectKey?.let { if (deleteObject(it)) deleted++ else failed++ }
        }
        log.info("[CleanupController] inbox: drained={} deleted={} failed={}", events.size, deleted, failed)
        return ResponseEntity.ok(InboxCleanupResponse(drained = events.size, deleted = deleted, failed = failed))
    }

    private fun deleteObject(objectKey: String): Boolean = try {
        objectStorage.delete(objectKey)
        true
    } catch (ex: Exception) {
        log.error("[CleanupController] delete failed: {} - {}", objectKey, ex.message, ex)
        false
    }
}

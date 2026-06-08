package maple.cleanup.controller

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import maple.cleanup.inbox.ConsumedChunkInbox
import maple.cleanup.inbox.InboxProperties
import maple.cleanup.service.RunCleanupService
import maple.common.cleanup.RunCleanupResult
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
 * - POST /api/internal/cleanup/inbox           → drain event queue + delete file per event
 *
 * Inbox deleteFile uses InboxProperties.basePath (NOT hardcoded).
 */
@RestController
@RequestMapping("/api/internal/cleanup")
class CleanupController(
    private val runCleanupService: RunCleanupService,
    private val inbox: ConsumedChunkInbox,
    private val inboxProperties: InboxProperties,
) {
    private val log = LoggerFactory.getLogger(CleanupController::class.java)

    @PostMapping("/runs")
    fun cleanupRuns(): ResponseEntity<RunCleanupResult> {
        log.info("[CleanupController] POST /runs")
        return ResponseEntity.ok(runCleanupService.cleanupRuns())
    }

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
            if (deleteFile(event.objectKey)) deleted++ else failed++
            event.sourceObjectKey?.let { if (deleteFile(it)) deleted++ else failed++ }
        }
        log.info("[CleanupController] inbox: drained={} deleted={} failed={}", events.size, deleted, failed)
        return ResponseEntity.ok(InboxCleanupResponse(drained = events.size, deleted = deleted, failed = failed))
    }

    private fun deleteFile(objectKey: String): Boolean = try {
        val path: Path = Paths.get(inboxProperties.basePath, objectKey)
        Files.deleteIfExists(path)
    } catch (ex: java.io.IOException) {
        log.error("[CleanupController] delete failed: {} - {}", objectKey, ex.message, ex)
        false
    }
}

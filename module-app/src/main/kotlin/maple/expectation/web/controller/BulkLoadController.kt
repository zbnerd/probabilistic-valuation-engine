package maple.expectation.web.controller

import maple.expectation.infrastructure.bulk.BulkLoaderService
import maple.expectation.infrastructure.bulk.BulkLoaderService.BulkLoadStatus
import maple.expectation.infrastructure.bulk.BulkLoaderService.LoadResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

/**
 * Bulk Load REST API Controller for Issue #611
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>POST /api/admin/bulk/load - Start bulk load from CSV (non-blocking, returns 202)</li>
 *   <li>POST /api/admin/bulk/resume - Resume from checkpoint</li>
 *   <li>POST /api/admin/bulk/retry-failed - Retry failed characters</li>
 *   <li>GET /api/admin/bulk/status - Get current status</li>
 * </ul>
 *
 * <h3>Security:</h3>
 * <p>Requires ADMIN role for access.
 *
 * <h3>Async Behavior:</h3>
 * <p>POST /load starts the job asynchronously and returns immediately with 202 Accepted.
 * Use GET /status to monitor progress.
 */
@RestController
@RequestMapping("/api/admin/bulk")
class BulkLoadController(
    private val bulkLoaderService: BulkLoaderService,
) {

    /**
     * Start bulk load from CSV (non-blocking)
     *
     * Returns immediately with 202 Accepted if job started successfully.
     * Use GET /status to monitor progress.
     *
     * @param force 캐시 무시 여부
     * @return 202 Accepted with message, or 409 Conflict if already running
     */
    @PostMapping("/load")
    fun startLoad(
        @RequestParam(name = "force", defaultValue = "false") force: Boolean,
    ): ResponseEntity<Map<String, String>> {
        // Check if already running
        val currentStatus = bulkLoaderService.getStatus()
        if (currentStatus.isRunning) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "error" to "Bulk load already running",
                    "message" to "Loaded ${currentStatus.loadedCount}/${currentStatus.totalCharacters} chars, ${currentStatus.ratePerSecond} chars/sec",
                )
            )
        }

        // Start asynchronously - don't wait for completion
        bulkLoaderService.loadAll(force = force)

        // Return immediately
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "message" to "Bulk load started",
                "status" to "Use GET /status to monitor progress"
            )
        )
    }

    /**
     * Resume bulk load from checkpoint
     *
     * @return LoadResult with statistics
     */
    /**
     * Resume bulk load from checkpoint
     *
     * @return 202 Accepted if started, or 409 Conflict if already running
     */
    @PostMapping("/resume")
    fun resume(): ResponseEntity<Map<String, String>> {
        val currentStatus = bulkLoaderService.getStatus()
        if (currentStatus.isRunning) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "error" to "Bulk load already running",
                    "message" to "Cannot resume while job is running"
                )
            )
        }

        // Start asynchronously
        bulkLoaderService.resume()

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "message" to "Bulk load resumed from checkpoint",
                "status" to "Use GET /status to monitor progress"
            )
        )
    }

    /**
     * Retry failed characters
     *
     * @return 202 Accepted if started, or 409 Conflict if already running
     */
    @PostMapping("/retry-failed")
    fun retryFailed(): ResponseEntity<Map<String, String>> {
        val currentStatus = bulkLoaderService.getStatus()
        if (currentStatus.isRunning) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "error" to "Bulk load already running",
                    "message" to "Cannot retry while job is running"
                )
            )
        }

        // Start asynchronously
        bulkLoaderService.retryFailed()

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "message" to "Retry failed characters started",
                "status" to "Use GET /status to monitor progress"
            )
        )
    }

    /**
     * Get current bulk load status
     *
     * @return BulkLoadStatus with current progress
     */
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<BulkLoadStatus> {
        val status = bulkLoaderService.getStatus()
        return ResponseEntity.ok(status)
    }

    /**
     * Stop bulk load
     *
     * @return Success message
     */
    @PostMapping("/stop")
    fun stop(): ResponseEntity<Map<String, String>> {
        bulkLoaderService.stop()
        return ResponseEntity.ok(mapOf("message" to "Bulk load stopped"))
    }
}

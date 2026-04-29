package maple.expectation.web.controller

import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.BulkLoadPort
import maple.expectation.core.port.inbound.BulkLoadResult
import maple.expectation.core.port.inbound.BulkLoadStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Bulk Load REST API Controller for Issue #611
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>POST /api/admin/bulk/load - Start bulk load from CSV</li>
 *   <li>POST /api/admin/bulk/resume - Resume from checkpoint</li>
 *   <li>POST /api/admin/bulk/retry-failed - Retry failed characters</li>
 *   <li>GET /api/admin/bulk/status - Get current status</li>
 * </ul>
 *
 * <h3>Security:</h3>
 * <p>Requires ADMIN role for access.
 */
@RestController
@RequestMapping("/api/admin/bulk")
class BulkLoadController(
    private val bulkLoadPort: BulkLoadPort,
) {

    /**
     * Start bulk load from CSV
     *
     * @param force 캐시 무시 여부
     * @return BulkLoadResult with statistics
     */
    @PostMapping("/load")
    fun startLoad(
        @RequestParam(name = "force", defaultValue = "false") force: Boolean,
    ): CompletableFuture<ResponseEntity<BulkLoadResult>> = bulkLoadPort.loadAll(force = force).thenApply { ResponseEntity.ok(it) }

    /**
     * Resume bulk load from checkpoint
     *
     * @return BulkLoadResult with statistics
     */
    @PostMapping("/resume")
    fun resume(): CompletableFuture<ResponseEntity<BulkLoadResult>> = bulkLoadPort.resume().thenApply { ResponseEntity.ok(it) }

    /**
     * Retry failed characters
     *
     * @return BulkLoadResult with statistics
     */
    @PostMapping("/retry-failed")
    fun retryFailed(): CompletableFuture<ResponseEntity<BulkLoadResult>> = bulkLoadPort.retryFailed().thenApply { ResponseEntity.ok(it) }

    /**
     * Get current bulk load status
     *
     * @return BulkLoadStatus with current progress
     */
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<BulkLoadStatus> {
        val status = bulkLoadPort.getStatus()
        return ResponseEntity.ok(status)
    }

    /**
     * Stop bulk load
     *
     * @return Success message
     */
    @PostMapping("/stop")
    fun stop(): ResponseEntity<Map<String, String>> {
        bulkLoadPort.stop()
        return ResponseEntity.ok(mapOf("message" to "Bulk load stopped"))
    }
}

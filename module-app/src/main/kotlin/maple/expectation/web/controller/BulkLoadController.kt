package maple.expectation.web.controller

import maple.expectation.infrastructure.bulk.BulkLoaderService
import maple.expectation.infrastructure.bulk.BulkLoaderService.BulkLoadStatus
import maple.expectation.infrastructure.bulk.BulkLoaderService.LoadResult
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
    private val bulkLoaderService: BulkLoaderService,
) {

    /**
     * Start bulk load from CSV
     *
     * @param force 캐시 무시 여부
     * @return LoadResult with statistics
     */
    @PostMapping("/load")
    fun startLoad(
        @RequestParam(name = "force", defaultValue = "false") force: Boolean,
    ): ResponseEntity<LoadResult> {
        val result = bulkLoaderService.loadAll(force = force).join()
        return ResponseEntity.ok(result)
    }

    /**
     * Resume bulk load from checkpoint
     *
     * @return LoadResult with statistics
     */
    @PostMapping("/resume")
    fun resume(): ResponseEntity<LoadResult> {
        val result = bulkLoaderService.resume().join()
        return ResponseEntity.ok(result)
    }

    /**
     * Retry failed characters
     *
     * @return LoadResult with statistics
     */
    @PostMapping("/retry-failed")
    fun retryFailed(): ResponseEntity<LoadResult> {
        val result = bulkLoaderService.retryFailed().join()
        return ResponseEntity.ok(result)
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

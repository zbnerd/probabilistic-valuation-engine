package maple.expectation.core.port.inbound

import java.util.concurrent.CompletableFuture

/**
 * Port for bulk loading character data from CSV with checkpointing,
 * progress tracking, and retry functionality.
 *
 * Used by BulkLoadController in module-web.
 */
interface BulkLoadPort {

    /**
     * Start bulk load from CSV file
     *
     * @param csvPath Path to CSV file (optional)
     * @param force Cache bypass flag
     * @return CompletableFuture with load result
     */
    fun loadAll(csvPath: String? = null, force: Boolean = false): CompletableFuture<BulkLoadResult>

    /**
     * Resume bulk load from checkpoint
     *
     * @return CompletableFuture with load result
     */
    fun resume(): CompletableFuture<BulkLoadResult>

    /**
     * Retry failed characters
     *
     * @return CompletableFuture with load result
     */
    fun retryFailed(): CompletableFuture<BulkLoadResult>

    /**
     * Get current bulk load status
     *
     * @return Current status
     */
    fun getStatus(): BulkLoadStatus

    /**
     * Stop bulk load operation
     */
    fun stop()
}

/**
 * Result of bulk load operation
 */
data class BulkLoadResult(
    val totalCharacters: Int,
    val loadedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val error: String? = null,
)

/**
 * Current bulk load status
 */
data class BulkLoadStatus(
    val isRunning: Boolean,
    val loadedCount: Int,
    val totalCharacters: Int,
    val errorCount: Int,
    val ratePerSecond: Double,
    val etaMinutes: Int,
)

package maple.expectation.application.adapter

import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.BulkLoadPort
import maple.expectation.core.port.inbound.BulkLoadResult
import maple.expectation.core.port.inbound.BulkLoadStatus
import maple.expectation.infrastructure.bulk.BulkLoaderService
import maple.expectation.infrastructure.bulk.BulkLoaderService.BulkLoadStatus as InfraBulkLoadStatus
import maple.expectation.infrastructure.bulk.BulkLoaderService.LoadResult
import org.springframework.stereotype.Component

/**
 * Adapter for BulkLoadPort that delegates to BulkLoaderService.
 *
 * Converts between core port types and infrastructure types.
 */
@Component
class BulkLoadPortAdapter(
    private val bulkLoaderService: BulkLoaderService,
) : BulkLoadPort {

    override fun loadAll(csvPath: String?, force: Boolean): CompletableFuture<BulkLoadResult> = bulkLoaderService.loadAll(csvPath, force).thenApply { it.toPortType() }

    override fun resume(): CompletableFuture<BulkLoadResult> = bulkLoaderService.resume().thenApply { it.toPortType() }

    override fun retryFailed(): CompletableFuture<BulkLoadResult> = bulkLoaderService.retryFailed().thenApply { it.toPortType() }

    override fun getStatus(): BulkLoadStatus = bulkLoaderService.getStatus().toPortType()

    override fun stop() {
        bulkLoaderService.stop()
    }

    private fun LoadResult.toPortType(): BulkLoadResult = BulkLoadResult(
        totalCharacters = totalCharacters,
        loadedCount = loadedCount,
        failedCount = failedCount,
        skippedCount = skippedCount,
        durationMs = durationMs,
        error = error,
    )

    private fun InfraBulkLoadStatus.toPortType(): BulkLoadStatus = BulkLoadStatus(
        isRunning = isRunning,
        loadedCount = loadedCount,
        totalCharacters = totalCharacters,
        errorCount = errorCount,
        ratePerSecond = ratePerSecond,
        etaMinutes = etaMinutes,
    )
}

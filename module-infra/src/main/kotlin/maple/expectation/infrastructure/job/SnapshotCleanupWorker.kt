package maple.expectation.infrastructure.job

import java.time.Instant
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class SnapshotCleanupWorker(
    private val snapshotRepository: CalculationSnapshotRepository,
    private val snapshotStore: SnapshotObjectStore,
    private val executor: LogicExecutor,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${snapshot.cleanup.interval-ms:3600000}")
    fun cleanupExpiredSnapshots() {
        val context = TaskContext.of("SnapshotCleanup", "Cleanup", "expired")

        executor.executeVoid({
            val cutoff = Instant.now()
            val expired = snapshotRepository.findByExpiresAtBefore(cutoff)
            if (expired.isEmpty()) return@executeVoid

            // Delete files first (outside TX — file ops are not transactional)
            for (snapshot in expired) {
                executor.executeOrDefault(
                    {
                        snapshotStore.delete(snapshot.objectKey)
                        null
                    },
                    null,
                    TaskContext.of("SnapshotCleanup", "DeleteFile", snapshot.objectKey),
                )
            }

            // Batch delete metadata in TX
            transactionTemplate.executeWithoutResult {
                snapshotRepository.deleteAll(expired)
            }
            log.info("Cleaned up {} expired snapshots", expired.size)
        }, context)
    }
}

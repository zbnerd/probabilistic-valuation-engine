package maple.expectation.infrastructure.job

import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SnapshotCleanupWorker(
    private val snapshotRepository: CalculationSnapshotRepository,
    private val snapshotStore: SnapshotObjectStore,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${snapshot.cleanup.interval-ms:3600000}")
    fun cleanupExpiredSnapshots() {
        val context = TaskContext.of("SnapshotCleanup", "Cleanup", "expired")

        executor.executeVoid({
            val cutoff = Instant.now()
            val expired = snapshotRepository.findByExpiresAtBefore(cutoff)
            if (expired.isEmpty()) return@executeVoid

            var deleted = 0
            for (snapshot in expired) {
                snapshotStore.delete(snapshot.objectKey)
                snapshotRepository.delete(snapshot)
                deleted++
            }
            log.info("Cleaned up {} expired snapshots", deleted)
        }, context)
    }
}

package maple.externalapi.cleanup

import maple.common.cleanup.RunInfo
import maple.common.cleanup.RunRetentionPolicy
import maple.externalapi.metrics.CleanupMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(name = ["external-api.cleanup.enabled"], havingValue = "true")
class ArtifactCleanupScheduler(
    private val artifactStore: ExternalApiArtifactStorePort,
    private val metrics: CleanupMetrics,
    @Value("\${external-api.cleanup.dry-run:true}")
    private val dryRun: Boolean,
    @Value("\${external-api.cleanup.runs.keep-recent:5}")
    private val keepRecent: Int,
    @Value("\${external-api.cleanup.runs.keep-within-hours:48}")
    private val keepWithinHours: Long,
    @Value("\${external-api.cleanup.max-delete-runs-per-cycle:10}")
    private val maxDeleteRunsPerCycle: Int,
    @Value("\${external-api.cleanup.max-delete-bytes-per-cycle:5368709120}")
    private val maxDeleteBytesPerCycle: Long,
    @Value("\${external-api.cleanup.max-runtime-seconds:60}")
    private val maxRuntimeSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(ArtifactCleanupScheduler::class.java)

    private val runIdPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    @Scheduled(fixedDelayString = "\${external-api.cleanup.interval-ms:21600000}")
    fun cleanup() {
        val sample = io.micrometer.core.instrument.Timer.start()
        val start = Instant.now()
        log.info("[Cleanup] started: dryRun={}", dryRun)

        updateStorageMetrics()

        val result = runCatching { cleanupRuns(start) }

        val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()
        sample.stop(metrics.timer())

        result.onSuccess { res ->
            log.info(
                "[Cleanup] completed: dryRun={}, runsDeleted={}, bytesDeleted={}, " +
                    "throttled={}, errors={}, durationMs={}",
                dryRun, res.runsDeleted, res.bytesDeleted, res.throttled, res.errors, durationMs,
            )
        }.onFailure { ex ->
            metrics.recordError()
            log.error("[Cleanup] failed (pipeline NOT affected): {}", ex.message, ex)
        }
    }

    private fun cleanupRuns(startedAt: Instant): CleanupResult {
        val runIds = artifactStore.listRuns()
        if (runIds.isEmpty()) {
            log.info("[Cleanup] no runs found")
            return CleanupResult.ZERO
        }

        var skippedActive = 0
        val runInfos = runIds.mapNotNull { runId ->
            val isRunning = artifactStore.fileExists("runs/$runId/_RUNNING")
            if (isRunning) {
                skippedActive++
                metrics.recordSkippedActive()
                return@mapNotNull null
            }
            val createdAt = parseRunIdTimestamp(runId) ?: return@mapNotNull null
            RunInfo(
                runId = runId,
                createdAt = createdAt,
                isRunning = false,
                sizeBytes = artifactStore.calculateDirectorySize("runs/$runId"),
            )
        }

        log.info("[Cleanup] scanned {} runs, skipped {} active, {} parseable", runIds.size, skippedActive, runInfos.size)

        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runInfos,
            keepRecentCount = keepRecent,
            keepWithinHours = keepWithinHours,
            now = Instant.now(),
        )

        if (toDelete.isEmpty()) {
            log.info("[Cleanup] no runs to delete")
            return CleanupResult.ZERO
        }

        val throttled = maxOf(0, toDelete.size - maxDeleteRunsPerCycle)
        val limited = if (toDelete.size > maxDeleteRunsPerCycle) {
            log.info("[Cleanup] throttling: {} candidates, limit {}", toDelete.size, maxDeleteRunsPerCycle)
            metrics.recordThrottled(throttled)
            toDelete.take(maxDeleteRunsPerCycle)
        } else {
            toDelete
        }

        log.info(
            "[Cleanup] candidates: {} of {} scanned (throttled={}, dryRun={})",
            limited.size, runInfos.size, maxOf(0, throttled), dryRun,
        )

        if (dryRun) {
            limited.forEach { run ->
                log.info(
                    "[Cleanup] would delete: runId={}, size={}MB, createdAt={}",
                    run.runId, run.sizeBytes / (1024 * 1024), run.createdAt,
                )
            }
            return CleanupResult(limited.size, limited.sumOf { it.sizeBytes }, 0, maxOf(0, throttled))
        }

        return deleteRunWithLimits(limited, startedAt)
    }

    private fun deleteRunWithLimits(runs: List<RunInfo>, startedAt: Instant): CleanupResult {
        var deletedRuns = 0
        var deletedBytes = 0L
        var errors = 0

        for (run in runs) {
            val elapsed = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            if (elapsed > maxRuntimeSeconds * 1000) {
                log.info("[Cleanup] runtime limit reached: {}ms > {}ms, stopping", elapsed, maxRuntimeSeconds * 1000)
                break
            }
            if (deletedBytes >= maxDeleteBytesPerCycle) {
                log.info("[Cleanup] byte limit reached: {} >= {}, stopping", deletedBytes, maxDeleteBytesPerCycle)
                break
            }

            val bytes = artifactStore.deleteRun(run.runId)
            if (bytes >= 0) {
                deletedRuns++
                deletedBytes += bytes
                metrics.recordDeletedBytes(bytes)
            } else {
                errors++
                metrics.recordError()
                log.warn("[Cleanup] failed to delete run: {} (pipeline NOT affected)", run.runId)
            }
        }

        metrics.recordDeletedRuns(deletedRuns)
        return CleanupResult(deletedRuns, deletedBytes, errors, 0)
    }

    private fun parseRunIdTimestamp(runId: String): Instant? {
        return runIdPattern.parse(runId) { Instant.from(it) }
    }

    private fun updateStorageMetrics() {
        val runsSize = artifactStore.calculateDirectorySize("runs")
        metrics.updateStorageUsed(runsSize)
    }

    private data class CleanupResult(
        val runsDeleted: Int,
        val bytesDeleted: Long,
        val errors: Int,
        val throttled: Int,
    ) {
        companion object {
            val ZERO = CleanupResult(0, 0L, 0, 0)
        }
    }
}

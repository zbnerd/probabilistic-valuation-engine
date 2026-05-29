package maple.externalapi.cleanup

import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import maple.externalapi.metrics.CleanupMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
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
    private val cleanupExecutor = RunCleanupExecutor("Cleanup")

    private val runIdPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

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

    private fun cleanupRuns(startedAt: Instant): RunCleanupResult {
        val runIds = artifactStore.listRuns()
        if (runIds.isEmpty()) {
            log.info("[Cleanup] no runs found")
            return RunCleanupResult.ZERO
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

        return cleanupExecutor.cleanup(
            runs = runInfos,
            dryRun = dryRun,
            keepRecent = keepRecent,
            keepWithinHours = keepWithinHours,
            now = Instant.now(),
            maxDeleteRunsPerCycle = maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = maxDeleteBytesPerCycle,
            maxRuntimeSeconds = maxRuntimeSeconds,
            startedAt = startedAt,
            deleteRun = { run -> artifactStore.deleteRun(run.runId) },
            onThrottled = { metrics.recordThrottled(it) },
            onDeletedBytes = { metrics.recordDeletedBytes(it) },
            onDeletedRuns = { metrics.recordDeletedRuns(it) },
            onDeleteError = { metrics.recordError() },
            onDryRunCandidate = { run ->
                log.info(
                    "[Cleanup] would delete: runId={}, size={}MB, createdAt={}",
                    run.runId, run.sizeBytes / (1024 * 1024), run.createdAt,
                )
            },
        )
    }

    private fun parseRunIdTimestamp(runId: String): Instant? {
        return runIdPattern.parse(runId) { Instant.from(it) }
    }

    private fun updateStorageMetrics() {
        val runsSize = artifactStore.calculateDirectorySize("runs")
        metrics.updateStorageUsed(runsSize)
    }

}

package maple.calculator.cleanup

import maple.calculator.storage.ObjectStorage
import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["calculator.cleanup.enabled"], havingValue = "true")
class CalculatorResultCleanupScheduler(
    private val objectStorage: ObjectStorage,
    @Value("\${calculator.cleanup.dry-run:true}")
    private val dryRun: Boolean,
    @Value("\${calculator.cleanup.runs.keep-recent:5}")
    private val keepRecent: Int,
    @Value("\${calculator.cleanup.runs.keep-within-hours:48}")
    private val keepWithinHours: Long,
    @Value("\${calculator.cleanup.max-delete-runs-per-cycle:10}")
    private val maxDeleteRunsPerCycle: Int,
    @Value("\${calculator.cleanup.max-delete-bytes-per-cycle:5368709120}")
    private val maxDeleteBytesPerCycle: Long,
    @Value("\${calculator.cleanup.max-runtime-seconds:60}")
    private val maxRuntimeSeconds: Long,
    @Value("\${calculator.store.input-base-path:../data}")
    private val basePath: String,
) {
    private val log = LoggerFactory.getLogger(CalculatorResultCleanupScheduler::class.java)
    private val cleanupExecutor = RunCleanupExecutor("CalculatorCleanup")

    @Scheduled(fixedDelayString = "\${calculator.cleanup.interval-ms:21600000}")
    fun cleanup() {
        Thread.ofVirtual().name("cleanup-calc").start {
            val start = Instant.now()
            log.info("[CalculatorCleanup] started: dryRun={}", dryRun)

            // Pipeline isolation: catch everything, never propagate
            val result = runCatching { cleanupRuns(start) }

            val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()

            result.onSuccess { res ->
                log.info(
                    "[CalculatorCleanup] completed: dryRun={}, deleted={}, bytes={}, " +
                        "errors={}, throttled={}, durationMs={}",
                    dryRun, res.runsDeleted, res.bytesDeleted, res.errors, res.throttled, durationMs,
                )
            }.onFailure { ex ->
                log.error("[CalculatorCleanup] failed (pipeline NOT affected): {}", ex.message, ex)
            }
        }
    }

    private fun cleanupRuns(startedAt: Instant): RunCleanupResult {
        val prefix = "data/calculator/runs"
        val runDirs = objectStorage.listDirectories(prefix)
        if (runDirs.isEmpty()) {
            log.info("[CalculatorCleanup] no calculator runs found")
            return RunCleanupResult.ZERO
        }

        val runInfos = runDirs.mapNotNull { parseRunInfo(prefix, it) }

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
            deleteRun = { run -> objectStorage.deleteDirectory("data/calculator/runs/${run.runId}") },
            onDryRunCandidate = { run ->
                log.info(
                    "[CalculatorCleanup] would delete: runId={}, size={}MB",
                    run.runId, run.sizeBytes / (1024 * 1024),
                )
            },
        )
    }

    private fun parseRunInfo(prefix: String, runId: String): RunInfo? {
        val fullPath = "$prefix/$runId"
        val sizeBytes = objectStorage.calculateDirectorySize(fullPath)
        val createdAt = readDirectoryCreatedTime(fullPath) ?: return null
        val isRunning = isRecentlyModified(fullPath)
        return RunInfo(
            runId = runId,
            createdAt = createdAt,
            isRunning = isRunning,
            sizeBytes = sizeBytes,
        )
    }

    private fun readDirectoryCreatedTime(prefix: String): Instant? {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return null
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        return Instant.ofEpochMilli(attrs.creationTime().toMillis())
    }

    private fun isRecentlyModified(prefix: String): Boolean {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return false
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val modifiedAt = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
        return modifiedAt.isAfter(Instant.now().minusSeconds(1800))
    }

}

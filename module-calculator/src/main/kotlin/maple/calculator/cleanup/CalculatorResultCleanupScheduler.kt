package maple.calculator.cleanup

import maple.calculator.storage.ObjectStorage
import maple.common.cleanup.RunInfo
import maple.common.cleanup.RunRetentionPolicy
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
    @Value("\${calculator.store.input-base-path:./external-api-data}")
    private val basePath: String,
) {
    private val log = LoggerFactory.getLogger(CalculatorResultCleanupScheduler::class.java)

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
                    dryRun, res.deleted, res.bytes, res.errors, res.throttled, durationMs,
                )
            }.onFailure { ex ->
                log.error("[CalculatorCleanup] failed (pipeline NOT affected): {}", ex.message, ex)
            }
        }
    }

    private fun cleanupRuns(startedAt: Instant): CleanupResult {
        val prefix = "data/calculator/runs"
        val runDirs = objectStorage.listDirectories(prefix)
        if (runDirs.isEmpty()) {
            log.info("[CalculatorCleanup] no calculator runs found")
            return CleanupResult.ZERO
        }

        val runInfos = runDirs.mapNotNull { parseRunInfo(prefix, it) }

        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runInfos,
            keepRecentCount = keepRecent,
            keepWithinHours = keepWithinHours,
            now = Instant.now(),
        )

        if (toDelete.isEmpty()) {
            log.info("[CalculatorCleanup] no runs to delete")
            return CleanupResult.ZERO
        }

        // Apply throttling limits
        val throttled = maxOf(0, toDelete.size - maxDeleteRunsPerCycle)
        val limited = if (toDelete.size > maxDeleteRunsPerCycle) {
            log.info("[CalculatorCleanup] throttling: {} candidates, limit {}", toDelete.size, maxDeleteRunsPerCycle)
            toDelete.take(maxDeleteRunsPerCycle)
        } else {
            toDelete
        }

        log.info(
            "[CalculatorCleanup] candidates: {} of {} (throttled={}, dryRun={})",
            limited.size, runInfos.size, throttled, dryRun,
        )

        if (dryRun) {
            limited.forEach { run ->
                log.info(
                    "[CalculatorCleanup] would delete: runId={}, size={}MB",
                    run.runId, run.sizeBytes / (1024 * 1024),
                )
            }
            return CleanupResult(limited.size, limited.sumOf { it.sizeBytes }, 0, throttled)
        }

        return deleteRunWithLimits(limited, startedAt)
    }

    private fun deleteRunWithLimits(runs: List<RunInfo>, startedAt: Instant): CleanupResult {
        var deleted = 0
        var bytes = 0L
        var errors = 0

        for (run in runs) {
            // Check runtime limit
            val elapsed = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            if (elapsed > maxRuntimeSeconds * 1000) {
                log.info("[CalculatorCleanup] runtime limit reached: {}ms, stopping", elapsed)
                break
            }
            // Check byte limit
            if (bytes >= maxDeleteBytesPerCycle) {
                log.info("[CalculatorCleanup] byte limit reached: {} bytes, stopping", bytes)
                break
            }

            val deletedBytes = objectStorage.deleteDirectory("data/calculator/runs/${run.runId}")
            if (deletedBytes >= 0) {
                deleted++
                bytes += deletedBytes
            } else {
                errors++
                log.warn("[CalculatorCleanup] failed to delete: {} (pipeline NOT affected)", run.runId)
            }
        }

        return CleanupResult(deleted, bytes, errors, 0)
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

    private data class CleanupResult(
        val deleted: Int,
        val bytes: Long,
        val errors: Int,
        val throttled: Int,
    ) {
        companion object {
            val ZERO = CleanupResult(0, 0L, 0, 0)
        }
    }
}

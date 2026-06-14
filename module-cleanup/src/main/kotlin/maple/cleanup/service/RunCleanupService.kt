package maple.cleanup.service

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Whole-run GC. Wraps the shared RunCleanupExecutor with an object-storage
 * prefix so the same service can target either runs/ (ext source) or
 * calculator/runs/ (calc result).
 *
 * runId format: yyyyMMdd-HHmmss-{nanoseconds}. Timestamp is parsed from the runId
 * (not filesystem ctime, which on Linux is inode creation time and not reliable).
 *
 * No @Scheduled — caller (Airflow HTTP trigger) is responsible for timing.
 */
@Service
class RunCleanupService(
    private val properties: CleanupProperties,
    private val objectStorage: ObjectStorage,
) {
    private val log = LoggerFactory.getLogger(RunCleanupService::class.java)
    private val cleanupExecutor = RunCleanupExecutor("Cleanup")
    private val runIdPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun cleanupRuns(): RunCleanupResult = cleanupPrefix("runs")
    fun cleanupCalculatorRuns(): RunCleanupResult = cleanupPrefix("calculator/runs")

    fun cleanupPrefix(prefix: String, now: Instant = Instant.now()): RunCleanupResult {
        val startedAt = Instant.now()
        log.info("[Cleanup] started prefix={} dryRun={}", prefix, properties.dryRun)

        val runIds = listRunIds(prefix)
        if (runIds.isEmpty()) {
            log.info("[Cleanup] no runs found at prefix={}", prefix)
            return RunCleanupResult.ZERO
        }

        val runInfos = runIds.mapNotNull { runId -> parseRunInfo(prefix, runId) }

        return cleanupExecutor.cleanup(
            runs = runInfos,
            dryRun = properties.dryRun,
            keepRecent = properties.runs.keepRecent,
            keepWithinHours = properties.runs.keepWithinHours,
            now = now,
            maxDeleteRunsPerCycle = properties.maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = properties.maxDeleteBytesPerCycle,
            maxRuntimeSeconds = properties.maxRuntimeSeconds,
            startedAt = startedAt,
            deleteRun = { run -> deleteRun(prefix, run.runId) },
        )
    }

    private fun listRunIds(prefix: String): List<String> {
        val keys = objectStorage.listByPrefix("$prefix/").map { it.key }
        return keys.mapNotNull { key ->
            val remainder = key.removePrefix(prefix).trimStart('/')
            remainder.substringBefore('/').takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    }

    private fun parseRunInfo(prefix: String, runId: String): RunInfo? {
        val runKey = "$prefix/$runId"
        if (objectStorage.exists("$runKey/_RUNNING")) {
            log.info("[Cleanup] skipping active run: {}", runId)
            return null
        }
        val timestamp = runId.substringBeforeLast("-")
        val createdAt = runCatching { runIdPattern.parse(timestamp) { Instant.from(it) } }
            .getOrElse {
                log.warn("[Cleanup] skipping unparseable runId: {}", runId)
                return null
            }
        val sizeBytes = objectStorage.calculatePrefixSize(runKey)
        return RunInfo(
            runId = runId,
            createdAt = createdAt,
            isRunning = false,
            sizeBytes = sizeBytes,
        )
    }

    private fun deleteRun(prefix: String, runId: String): Long {
        val runKey = "$prefix/$runId"
        val size = objectStorage.calculatePrefixSize(runKey)
        objectStorage.deleteByPrefix(runKey)
        return size
    }
}

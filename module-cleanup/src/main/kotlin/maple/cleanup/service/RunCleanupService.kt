package maple.cleanup.service

import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupExecutor
import maple.common.cleanup.RunCleanupResult
import maple.common.cleanup.RunInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Whole-run GC. Wraps the shared RunCleanupExecutor with a path prefix so the same
 * service can target either runs/ (ext source) or calculator/runs/ (calc result).
 *
 * runId format: yyyyMMdd-HHmmss-{nanoseconds}. Timestamp is parsed from the runId
 * (not filesystem ctime, which on Linux is inode creation time and not reliable).
 *
 * No @Scheduled — caller (Airflow HTTP trigger) is responsible for timing.
 */
@Service
class RunCleanupService(
    @Value("\${cleanup.base-path:../data}") private val basePath: String,
    private val properties: CleanupProperties,
) {
    private val log = LoggerFactory.getLogger(RunCleanupService::class.java)
    private val cleanupExecutor = RunCleanupExecutor("Cleanup")
    private val runIdPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun cleanupRuns(): RunCleanupResult = cleanupPrefix("runs")
    fun cleanupCalculatorRuns(): RunCleanupResult = cleanupPrefix("calculator/runs")

    fun cleanupPrefix(prefix: String): RunCleanupResult {
        val startedAt = Instant.now()
        log.info("[Cleanup] started prefix={} dryRun={}", prefix, properties.dryRun)

        val runDirs = listRunDirs(prefix)
        if (runDirs.isEmpty()) {
            log.info("[Cleanup] no runs found at {}/{}", basePath, prefix)
            return RunCleanupResult.ZERO
        }

        val runInfos = runDirs.mapNotNull { runId -> parseRunInfo(prefix, runId) }

        return cleanupExecutor.cleanup(
            runs = runInfos,
            dryRun = properties.dryRun,
            keepRecent = properties.runs.keepRecent,
            keepWithinHours = properties.runs.keepWithinHours,
            now = Instant.now(),
            maxDeleteRunsPerCycle = properties.maxDeleteRunsPerCycle,
            maxDeleteBytesPerCycle = properties.maxDeleteBytesPerCycle,
            maxRuntimeSeconds = properties.maxRuntimeSeconds,
            startedAt = startedAt,
            deleteRun = { run -> deleteDirectory("$prefix/${run.runId}") },
        )
    }

    private fun listRunDirs(prefix: String): List<String> {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return emptyList()
        return Files.list(path).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    private fun parseRunInfo(prefix: String, runId: String): RunInfo? {
        val fullPath = "$prefix/$runId"
        val runPath = Paths.get(basePath, fullPath)
        if (!Files.exists(runPath)) return null
        val runningMarker = Paths.get(basePath, "$fullPath/_RUNNING")
        if (Files.exists(runningMarker)) {
            log.info("[Cleanup] skipping active run: {}", runId)
            return null
        }
        val timestamp = runId.substringBeforeLast("-")
        val createdAt = runCatching { runIdPattern.parse(timestamp) { Instant.from(it) } }
            .getOrElse {
                log.warn("[Cleanup] skipping unparseable runId: {}", runId)
                return null
            }
        val sizeBytes = calculateDirectorySize(fullPath)
        return RunInfo(
            runId = runId,
            createdAt = createdAt,
            isRunning = false,
            sizeBytes = sizeBytes,
        )
    }

    private fun calculateDirectorySize(relativePath: String): Long {
        val path = Paths.get(basePath, relativePath)
        if (!Files.exists(path)) return 0L
        var total = 0L
        Files.walk(path).use { stream ->
            stream.filter(Files::isRegularFile).forEach { total += Files.size(it) }
        }
        return total
    }

    private fun deleteDirectory(relativePath: String): Long {
        val path = Paths.get(basePath, relativePath)
        if (!Files.exists(path)) return 0L
        var total = 0L
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                if (Files.isRegularFile(it)) total += Files.size(it)
                Files.deleteIfExists(it)
            }
        }
        return total
    }
}

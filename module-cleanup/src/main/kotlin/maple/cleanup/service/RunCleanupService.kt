package maple.cleanup.service

import maple.cleanup.config.CleanupProperties
import maple.common.cleanup.RunCleanupResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Whole-run GC. Tracer bullet: only the empty-path path is implemented.
 * Subsequent TDD cycles will add listRunDirs, parseRunInfo, deleteDirectory.
 */
@Service
class RunCleanupService(
    @Value("\${cleanup.base-path:../data}") private val basePath: String,
    private val properties: CleanupProperties,
) {
    fun cleanupRuns(): RunCleanupResult {
        val path = java.nio.file.Paths.get(basePath, "runs")
        if (!java.nio.file.Files.exists(path)) return RunCleanupResult.ZERO
        return RunCleanupResult.ZERO  // will be replaced by RunCleanupExecutor call in next cycle
    }
}

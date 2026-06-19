package maple.externalapi.snapshot

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class OrphanTempFileCleanupHook(
    private val executor: LogicExecutor,
    @Qualifier("loopExecutor")
    private val asyncExecutor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val scanDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    private val timeoutSeconds: Long = 30,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        executor.executeVoidJava(
            Runnable { runWithDeadline() },
            TaskContext.of("OrphanTempFileCleanup", "BootScan"),
        )
    }

    /**
     * Run [cleanupOrphans] on [asyncExecutor] bounded by [timeoutSeconds]. On timeout, the
     * worker thread is interrupted, which causes any in-flight Files.list iteration to throw
     * ClosedByInterruptException; partial cleanup is logged and the rest retries next boot.
     * On other failures (e.g. IOException from Files.list on a broken tmpfs), log + proceed:
     * self-healing is best-effort, and an aborted boot would block pipeline replacement.
     */
    internal fun runWithDeadline() {
        val future = try {
            CompletableFuture.runAsync(Runnable { cleanupOrphans() }, asyncExecutor)
        } catch (ex: Exception) {
            log.error("[OrphanTempFileCleanup] cleanup submit failed: {}", ex.message, ex)
            return
        }
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (ex: TimeoutException) {
            log.warn(
                "[OrphanTempFileCleanup] cleanup exceeded {}s; cancelling, will retry next boot",
                timeoutSeconds,
            )
            future.cancel(true)
        } catch (ex: Exception) {
            val cause = (ex as? ExecutionException)?.cause ?: ex
            log.error("[OrphanTempFileCleanup] cleanup failed: {}", cause.message, cause)
        }
    }

    private fun cleanupOrphans() {
        val cutoff = Instant.now(clock).minus(CUTOFF)
        var scanned = 0L
        var deleted = 0L
        var bytesFreed = 0L
        var failed = 0L

        Files.list(scanDir).use { stream ->
            stream
                .filter { ORPHAN_PATTERN.matches(it.fileName.toString()) }
                .forEach { file ->
                    scanned++
                    val mtime = try {
                        Files.getLastModifiedTime(file).toInstant()
                    } catch (ex: java.io.IOException) {
                        log.warn("[OrphanTempFileCleanup] read mtime failed for {}: {}", file, ex.message)
                        failed++
                        return@forEach
                    }
                    if (mtime.isBefore(cutoff)) {
                        try {
                            bytesFreed += Files.size(file)
                            Files.delete(file)
                            deleted++
                        } catch (ex: java.io.IOException) {
                            log.warn("[OrphanTempFileCleanup] delete failed for {}: {}", file, ex.message)
                            failed++
                        }
                    }
                }
        }

        log.info(
            "[OrphanTempFileCleanup] scanned={} deleted={} bytes_freed={} failed={}",
            scanned, deleted, bytesFreed, failed,
        )
        if (failed > 0) {
            log.warn("[OrphanTempFileCleanup] {} files failed to clean; will retry next boot", failed)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java)
        private val ORPHAN_PATTERN = Regex("gzip-chunk-.*\\.tmp")
        private val CUTOFF: Duration = Duration.ofHours(1)
    }
}
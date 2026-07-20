package maple.externalapi.snapshot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.MILLISECONDS
import maple.externalapi.metrics.OrphanCleanupMetrics
import maple.externalapi.metrics.OrphanCleanupResult
import maple.externalapi.metrics.OrphanCleanupSummary
import maple.pipeline.messaging.contract.CompletionFailures
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Component

@Component
class OrphanTempFileCleanupHook(
    @Qualifier("loopExecutor") private val asyncExecutor: AsyncTaskExecutor,
    private val metrics: OrphanCleanupMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val scanDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    private val timeout: Duration = Duration.ofSeconds(30),
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        runWithDeadline()
    }

    internal fun runWithDeadline() {
        val result = CompletableFuture<OrphanCleanupSummary>()
        result.whenComplete(::recordTerminalResult)

        val submission = runCatching {
            asyncExecutor.submit {
                runCatching(::cleanupOrphans)
                    .onSuccess { summary -> result.complete(summary) }
                    .onFailure { failure -> result.completeExceptionally(failure) }
            }
        }.onFailure { failure ->
            result.completeExceptionally(OrphanCleanupSubmissionFailure(failure))
        }
        val submitted = submission.getOrNull() ?: return

        CompletableFuture.runAsync(
            {
                if (result.completeExceptionally(OrphanCleanupTimeout())) {
                    submitted.cancel(true)
                }
            },
            CompletableFuture.delayedExecutor(
                timeout.toMillis(),
                MILLISECONDS,
                Executor { command -> command.run() },
            ),
        )
    }

    private fun cleanupOrphans(): OrphanCleanupSummary {
        val cutoff = Instant.now(clock).minus(CUTOFF)
        var scanned = 0L
        var deleted = 0L
        var bytesFreed = 0L
        var failed = 0L

        Files.list(scanDir).use { stream ->
            val candidates = stream
                .filter { ORPHAN_PATTERN.matches(it.fileName.toString()) }
                .iterator()
            while (candidates.hasNext()) {
                ensureNotInterrupted()
                val file = candidates.next()
                scanned++
                runCatching { cleanupCandidate(file, cutoff) }
                    .onSuccess { freed ->
                        if (freed != null) {
                            deleted++
                            bytesFreed += freed
                        }
                    }
                    .onFailure { failure ->
                        if (failure is InterruptedException) throw failure
                        failed++
                        log.warn(
                            "[OrphanTempFileCleanup] cleanup failed for file={}",
                            file,
                            failure,
                        )
                    }
            }
        }

        return OrphanCleanupSummary(scanned, deleted, bytesFreed, failed)
    }

    private fun cleanupCandidate(
        file: Path,
        cutoff: Instant,
    ): Long? {
        ensureNotInterrupted()
        if (!Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) return null

        ensureNotInterrupted()
        val size = Files.size(file)
        Files.delete(file)
        return size
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("orphan cleanup interrupted")
        }
    }

    private fun recordTerminalResult(
        summary: OrphanCleanupSummary?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            recordFailure(CompletionFailures.unwrap(failure))
            return
        }

        val completed = requireNotNull(summary)
        val result = if (completed.failed == 0L) {
            OrphanCleanupResult.SUCCESS
        } else {
            OrphanCleanupResult.FAILED
        }
        metrics.record(result, completed)
        log.info(
            "[OrphanTempFileCleanup] result={} scanned={} deleted={} bytes_freed={} failed={}",
            result.tagValue,
            completed.scanned,
            completed.deleted,
            completed.bytesFreed,
            completed.failed,
        )
        if (completed.failed > 0) {
            log.warn(
                "[OrphanTempFileCleanup] result=failed failed_files={}; will retry next boot",
                completed.failed,
            )
        }
    }

    private fun recordFailure(failure: Throwable) {
        when (failure) {
            is OrphanCleanupSubmissionFailure -> {
                metrics.record(OrphanCleanupResult.SUBMIT_FAILED, null)
                log.warn(
                    "[OrphanTempFileCleanup] result=submit_failed; startup will continue",
                    failure.cause,
                )
            }
            is OrphanCleanupTimeout -> {
                metrics.record(OrphanCleanupResult.TIMEOUT, null)
                log.warn(
                    "[OrphanTempFileCleanup] result=timeout timeout={}; will retry next boot",
                    timeout,
                )
            }
            else -> {
                metrics.record(OrphanCleanupResult.FAILED, null)
                log.warn(
                    "[OrphanTempFileCleanup] result=failed; will retry next boot",
                    failure,
                )
            }
        }
    }

    private class OrphanCleanupSubmissionFailure(
        cause: Throwable,
    ) : RuntimeException(cause)

    private class OrphanCleanupTimeout : RuntimeException("orphan cleanup timed out")

    private companion object {
        private val log = LoggerFactory.getLogger(OrphanTempFileCleanupHook::class.java)
        private val ORPHAN_PATTERN = Regex("gzip-chunk-.*\\.tmp")
        private val CUTOFF: Duration = Duration.ofHours(1)
    }
}

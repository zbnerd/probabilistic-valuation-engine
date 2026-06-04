package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object SchedulerPhaseUtils {
    private val log = LoggerFactory.getLogger(SchedulerPhaseUtils::class.java)

    fun newRateLimiter(permits: Int): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(permits.toLong())
                .refillIntervally(permits.toLong(), Duration.ofSeconds(1))
                .build(),
        )
        .build()

    /**
     * Suspend-friendly rate limit permit acquisition.
     * Replaces Thread.sleep(100) with coroutine delay(100) when no permits available.
     */
    suspend fun acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int {
        val maxBatch = minOf(batchSize, remaining)
        val consumed = rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()
        if (consumed == 0) {
            delay(100)
        }
        return consumed
    }

    fun newRunId(): String {
        val now = Instant.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        return "${formatter.format(now)}-${now.nano}"
    }

    fun writeRunningMarker(runDir: Path) {
        val marker = runDir.resolve("_RUNNING")
        Files.createDirectories(runDir)
        Files.writeString(marker, Instant.now().toString())
        log.info("[Scheduler] wrote _RUNNING marker: {}", marker)
    }

    fun extractHttpStatus(ex: Throwable): Int {
        val cause = if (ex is java.util.concurrent.CompletionException) ex.cause else ex
        return when (cause) {
            is org.springframework.web.reactive.function.client.WebClientResponseException -> cause.statusCode.value()
            else -> 0
        }
    }

    fun logProgress(phase: String, progress: Int, total: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(progress / elapsedSec) else "?"
        log.info(
            "[Scheduler] {}: {}/{} (success={}, fail={}, rate={}files/s, elapsed={}s)",
            phase, progress, total, stored, fails, rate, elapsedSec.toLong(),
        )
    }

    fun logSummary(phase: String, total: Int, success: Int, stored: Int, fails: Int, start: Instant) {
        val elapsedSec = Duration.between(start, Instant.now()).toMillis() / 1000.0
        val rate = if (elapsedSec > 0) "%.0f".format(total / elapsedSec) else "?"
        log.info("[Scheduler] ========== {} complete ==========", phase)
        log.info(
            "[Scheduler] result: total={}, success={}, fail={}, elapsed={}s, avgRate={}files/s",
            total, success, fails, elapsedSec.toLong(), rate,
        )
    }
}

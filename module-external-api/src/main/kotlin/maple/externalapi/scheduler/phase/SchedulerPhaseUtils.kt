package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bucket
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * Pre-existing-fix facade for the abandoned refactor that split
 * SchedulerPhaseUtils into RunIdGenerator / RunMarkerWriter /
 * SchedulerRateLimiter / SchedulerProgressLogger / HttpStatusExtractor.
 *
 * The phase classes (OcidLookupPhase, RankingFetchPhase) still call
 * `SchedulerPhaseUtils.foo()`. This object delegates to the extracted
 * utility classes using systemUTC clock.
 *
 * TODO(#1217 pre-existing): when the phase classes are migrated to
 * VS2's unified ObjectStorage, replace these static calls with injected
 * Spring beans and remove this facade.
 */
object SchedulerPhaseUtils {
    private val clock: Clock = Clock.systemUTC()
    private val runIdGenerator = RunIdGenerator(clock)
    private val runMarkerWriter = RunMarkerWriter(clock)
    private val rateLimiter = SchedulerRateLimiter()
    private val progressLogger = SchedulerProgressLogger(clock)
    private val httpStatusExtractor = HttpStatusExtractor()

    fun newRunId(): String = runIdGenerator.newRunId()
    fun writeRunningMarker(runDir: Path) = runMarkerWriter.writeRunningMarker(runDir)
    fun newRateLimiter(permits: Int): Bucket = rateLimiter.newRateLimiter(permits)

    /**
     * Sync version of acquirePermitsSuspend (the new class only has
     * the suspend variant). Returns 0 if no permits available; caller
     * is responsible for backoff (the suspend version has delay).
     */
    fun acquirePermits(bucket: Bucket, batchSize: Int, remaining: Int): Int {
        val maxBatch = minOf(batchSize, remaining)
        return bucket.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()
    }

    fun logProgress(
        phase: String,
        progress: Int,
        total: Int,
        stored: Int,
        fails: Int,
        start: Instant,
    ) = progressLogger.logProgress(phase, progress, total, stored, fails, start)

    fun logSummary(
        phase: String,
        total: Int,
        success: Int,
        stored: Int,
        fails: Int,
        start: Instant,
    ) = progressLogger.logSummary(phase, total, success, stored, fails, start)

    fun extractHttpStatus(ex: Throwable): Int = httpStatusExtractor.extract(ex)
}

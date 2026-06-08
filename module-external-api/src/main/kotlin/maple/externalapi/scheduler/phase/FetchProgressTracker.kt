package maple.externalapi.scheduler.phase

import maple.externalapi.metrics.SnapshotFetchMetrics
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Per-fetch progress tracker wrapping [BatchProgress] with the API surface called out by
 * issue #1062. Encapsulates success/fail count updates, per-fetch metric recording
 * (via [SnapshotFetchMetrics.recordFetchJoin]), and per-call queue-depth logging.
 *
 * State updates produce a new [BatchProgress] via `addSuccess` / `addFailure` so the
 * underlying progress remains immutable and safe to share across loops.
 *
 * @param progress  Initial batch progress (typically zeroed with the start time)
 * @param fetchMetrics  Fetch metrics (records per-fetch duration)
 * @param endpoint  Endpoint name (used as metric tag)
 */
class FetchProgressTracker(
    private var progress: BatchProgress,
    private val fetchMetrics: SnapshotFetchMetrics,
    private val endpoint: String,
) {
    private val log = LoggerFactory.getLogger(FetchProgressTracker::class.java)

    fun recordSuccess(ocid: String, duration: Duration, queueDepth: Int) {
        progress = progress.addSuccess(1)
        fetchMetrics.recordFetchJoin(endpoint, duration)
        log.debug(
            "[{}] fetch success: ocid={} duration={}ms queueDepth={}",
            endpoint,
            ocid,
            duration.toMillis(),
            queueDepth,
        )
    }

    fun recordFailure(ocid: String, ex: Throwable) {
        progress = progress.addFailure(1)
        log.warn(
            "[{}] fetch failed: ocid={} reason={}",
            endpoint,
            ocid,
            ex.message,
        )
    }

    fun snapshot(): BatchProgress = progress
}

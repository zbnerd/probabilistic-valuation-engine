package maple.externalapi.metrics

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class SnapshotFetchMetrics(private val registry: MeterRegistry) {

    fun recordNexonBodyReceived(endpoint: String, duration: Duration, bytes: Int) {
        timer("external_api_nexon_body_received_seconds", endpoint)
            .record(duration)
        summary("external_api_nexon_response_bytes", endpoint)
            .record(bytes.toDouble())
    }

    fun recordNexonFailure(endpoint: String, duration: Duration) {
        timer("external_api_nexon_failure_seconds", endpoint)
            .record(duration)
    }

    fun recordFetchJoin(endpoint: String, duration: Duration) {
        timer("external_api_snapshot_fetch_join_seconds", endpoint)
            .record(duration)
    }

    fun recordSinkSubmit(endpoint: String, duration: Duration, queueDepthBeforeSubmit: Int) {
        timer("external_api_snapshot_sink_submit_seconds", endpoint)
            .record(duration)
        summary("external_api_snapshot_sink_queue_depth", endpoint)
            .record(queueDepthBeforeSubmit.toDouble())
    }

    fun recordBatchWait(endpoint: String, duration: Duration, size: Int) {
        timer("external_api_snapshot_batch_wait_seconds", endpoint)
            .record(duration)
        summary("external_api_snapshot_batch_size", endpoint)
            .record(size.toDouble())
    }

    private fun timer(name: String, endpoint: String): Timer =
        Timer.builder(name)
            .tag("endpoint", endpoint)
            .register(registry)

    private fun summary(name: String, endpoint: String): DistributionSummary =
        DistributionSummary.builder(name)
            .tag("endpoint", endpoint)
            .register(registry)
}

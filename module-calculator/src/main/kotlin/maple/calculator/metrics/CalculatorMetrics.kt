package maple.calculator.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class CalculatorMetrics(registry: MeterRegistry) {
    private val chunksProcessed = registry.counter("calculator_chunks_processed_total")
    private val chunksSkipped = registry.counter("calculator_chunks_skipped_total", "reason", "endpoint_mismatch")
    private val chunksNotFound = registry.counter("calculator_chunks_skipped_total", "reason", "source_not_found")
    private val chunksIdempotent = registry.counter("calculator_chunks_skipped_total", "reason", "result_exists")
    private val chunksFailed = registry.counter("calculator_chunks_failed_total")

    private val usersProcessed = registry.counter("calculator_users_processed_total")
    private val itemsProcessed = registry.counter("calculator_items_processed_total")
    private val itemsCalculated = registry.counter("calculator_items_calculated_total")
    private val itemsErrored = registry.counter("calculator_items_errored_total")

    private val chunkTimer = Timer.builder("calculator_chunk_duration_seconds")
        .description("Time to process a single snapshot chunk")
        .register(registry)

    @Volatile private var lastChunkUsersPerSec = 0.0

    @Volatile private var lastChunkItemsPerSec = 0.0

    init {
        Gauge.builder("calculator_chunk_users_per_second") { lastChunkUsersPerSec }
            .description("Users processed per second in the last completed chunk")
            .register(registry)
        Gauge.builder("calculator_chunk_items_per_second") { lastChunkItemsPerSec }
            .description("Items processed per second in the last completed chunk")
            .register(registry)
    }

    fun recordChunkProcessed() = chunksProcessed.increment()
    fun recordChunkSkippedEndpoint() = chunksSkipped.increment()
    fun recordChunkSkippedNotFound() = chunksNotFound.increment()
    fun recordChunkSkippedIdempotent() = chunksIdempotent.increment()
    fun recordChunkFailed() = chunksFailed.increment()

    fun recordUsers(count: Int) = usersProcessed.increment(count.toDouble())
    fun recordItems(count: Int) = itemsProcessed.increment(count.toDouble())
    fun recordCalculated(count: Int) = itemsCalculated.increment(count.toDouble())
    fun recordErrors(count: Int) = itemsErrored.increment(count.toDouble())

    fun recordChunkRates(users: Int, items: Int, durationSec: Double) {
        if (durationSec > 0) {
            lastChunkUsersPerSec = users / durationSec
            lastChunkItemsPerSec = items / durationSec
        }
    }

    fun timer(): Timer = chunkTimer
}

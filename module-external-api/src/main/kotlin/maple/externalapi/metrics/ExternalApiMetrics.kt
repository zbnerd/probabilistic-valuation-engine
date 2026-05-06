package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class ExternalApiMetrics(registry: MeterRegistry) {
    private val usersFetched = registry.counter("external_users_fetched_total")
    private val usersFailed = registry.counter("external_users_failed_total")

    private val chunksCreated = registry.counter("external_chunks_created_total")

    private val lookupTimer = Timer.builder("external_lookup_duration_seconds")
        .description("Time for a full endpoint lookup run")
        .register(registry)

    fun recordFetched(count: Int = 1) = usersFetched.increment(count.toDouble())
    fun recordFailed(count: Int = 1) = usersFailed.increment(count.toDouble())
    fun recordChunkCreated() = chunksCreated.increment()

    fun timer(): Timer = lookupTimer
}

package maple.restcontroller.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.restcontroller.read.InflightRequestRegistry
import maple.restcontroller.read.LocalRequestBuffer

class V6ReadMetrics(
    meterRegistry: MeterRegistry,
    requestBuffer: LocalRequestBuffer,
    inflightRegistry: InflightRequestRegistry
) {
    val requestTotal: Counter = Counter.builder("v6_request_total")
        .description("Total V6 read requests")
        .register(meterRegistry)

    val dedupHitTotal: Counter = Counter.builder("v6_dedup_hit_total")
        .description("Inflight dedup cache hit count")
        .register(meterRegistry)

    val dedupMissTotal: Counter = Counter.builder("v6_dedup_miss_total")
        .description("Inflight dedup cache miss count")
        .register(meterRegistry)

    val timeoutTotal: Counter = Counter.builder("v6_timeout_total")
        .description("DeferredResult timeout to 202 count")
        .register(meterRegistry)

    val bufferRejectedTotal: Counter = Counter.builder("v6_buffer_rejected_total")
        .description("Buffer full to 503 rejection count")
        .register(meterRegistry)

    init {
        Gauge.builder("v6_buffer_size", requestBuffer) { it.size().toDouble() }
            .description("Current RequestBuffer queue size")
            .register(meterRegistry)

        Gauge.builder("v6_inflight_size", inflightRegistry) { it.size().toDouble() }
            .description("Current InflightRequestRegistry unique userIgn count")
            .register(meterRegistry)
    }
}

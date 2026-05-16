package maple.restcontroller.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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

    val urgentTriggerTotal: Counter = Counter.builder("v6_urgent_trigger_total")
        .description("Total urgent pipeline triggers")
        .register(meterRegistry)

    private val hitCounter: Counter = Counter.builder("v6_read_hit_total")
        .description("V6 read model cache hits")
        .register(meterRegistry)

    private val redisHitCounter: Counter = Counter.builder("v6_redis_hit_total")
        .description("V6 Redis cache hits")
        .register(meterRegistry)

    private val dbHitCounter: Counter = Counter.builder("v6_db_hit_total")
        .description("V6 DB query hits (cache miss -> DB fallback)")
        .register(meterRegistry)

    private val missCounters = mutableMapOf<String, Counter>()
    private val meterRegistry = meterRegistry

    val batchLatency: Timer = Timer.builder("v6_batch_latency")
        .description("V6 batch query latency")
        .register(meterRegistry)

    fun recordHit() = hitCounter.increment()

    fun recordRedisHit() = redisHitCounter.increment()

    fun recordDbHit() = dbHitCounter.increment()

    fun recordMiss(reason: String) {
        val counter = missCounters.getOrPut(reason) {
            Counter.builder("v6_read_miss_total")
                .tag("reason", reason)
                .description("V6 read model cache misses")
                .register(meterRegistry)
        }
        counter.increment()
    }

    init {
        Gauge.builder("v6_buffer_size", requestBuffer) { it.size().toDouble() }
            .description("Current RequestBuffer queue size")
            .register(meterRegistry)

        Gauge.builder("v6_inflight_size", inflightRegistry) { it.size().toDouble() }
            .description("Current InflightRequestRegistry unique userIgn count")
            .register(meterRegistry)
    }
}

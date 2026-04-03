package maple.expectation.infrastructure.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.worker.ExpectationCalcWorker
import maple.expectation.infrastructure.worker.ExpectationCalcLowWorker
import maple.expectation.infrastructure.queue.pgmq.FanOutQueueProducer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Queue Depth Metrics (ADR-355)
 *
 * <p>PGMQ 큐 깊이를 주기적으로 측정하여 Grafana 대시보드에 제공.
 */
@Component
class QueueMetrics(
    private val pgmqClient: PgmqClient,
    meterRegistry: MeterRegistry,
) {
    init {
        Gauge.builder("pgmq.queue.depth") { pgmqClient.queueLength(ExpectationCalcWorker.QUEUE_NAME).toDouble() }
            .description("PGMQ queue depth")
            .tag("queue", "expectation_high")
            .register(meterRegistry)

        Gauge.builder("pgmq.queue.depth") { pgmqClient.queueLength(ExpectationCalcLowWorker.QUEUE_NAME).toDouble() }
            .description("PGMQ queue depth")
            .tag("queue", "expectation_low")
            .register(meterRegistry)

        Gauge.builder("pgmq.queue.depth") { pgmqClient.queueLength(FanOutQueueProducer.QUEUE_NAME).toDouble() }
            .description("PGMQ queue depth")
            .tag("queue", "fanout_retry")
            .register(meterRegistry)
    }

    companion object {
        private val log = LoggerFactory.getLogger(QueueMetrics::class.java)
    }
}

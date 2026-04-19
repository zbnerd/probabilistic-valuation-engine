package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * PGMQ 큐/워커 종합 계측 (QueueMetrics + PgmqWorkerMetrics 통합 대체)
 *
 * <h3>Gauges (실시간)</h3>
 * <ul>
 *   <li>pgmq.queue.depth{queue} — 큐 대기 메시지 수 (워커 폴링 시 업데이트)
 *   <li>pgmq.worker.inflight{queue} — 읽었지만 완료되지 않은 메시지 수
 *   <li>pgmq.worker.concurrent{queue} — 현재 처리 중인 메시지 수 (= active worker)
 * </ul>
 *
 * <h3>Counters (누적)</h3>
 * <ul>
 *   <li>pgmq.worker.success.total{queue} — 처리 성공
 *   <li>pgmq.worker.failure.total{queue} — 처리 실패 (retry + dlq 포함)
 *   <li>pgmq.worker.retry.total{queue} — 재시도 (readCount 증가)
 *   <li>pgmq.worker.dlq.total{queue} — DLQ 이동 (max retries 초과)
 * </ul>
 *
 * <h3>Timer (히스토그램)</h3>
 * <ul>
 *   <li>pgmq.worker.wait.duration{queue} — 큐 대기 시간 (enqueuedAt → processStart)
 * </ul>
 *
 * <h3>Prometheus 파생 지표</h3>
 * <pre>{@code
 * # 처리량 (tasks/sec)
 * rate(pgmq_worker_success_total[1m])
 *
 * # 실패율
 * rate(pgmq_worker_failure_total[5m]) / rate(pgmq_worker_success_total[5m])
 *
 * # 큐 대기 시간 p99
 * histogram_quantile(0.99, rate(pgmq_worker_wait_duration_seconds_bucket{queue="expectation_calc_high"}[5m]))
 *
 * # 병목 큐 식별
 * topk(3, pgmq_queue_depth)
 * }</pre>
 *
 * <h3>Cardinality</h3>
 * <p>태그 = queue (고정값 ≤ 6). 총 time series ≤ 6 × 10 metrics = 60.
 *
 * <h3>Thread Safety</h3>
 * <p>모든 Gauge는 AtomicLong, Counter/Timer는 Micrometer 내부 thread-safe.
 * Virtual Thread 환경에서 안전.
 */
@Component
class WorkerQueueMetrics(private val registry: MeterRegistry) {

    private val binders = ConcurrentHashMap<String, Binder>()

    /** 큐별 메트릭 바인더 반환 (최초 호출 시 생성 및 등록) */
    fun forQueue(queueName: String): Binder = binders.computeIfAbsent(queueName) { Binder(registry, queueName) }

    /**
     * 큐별 메트릭 바인딩
     *
     * <p>PgmqWorker가 폴링 사이클마다 호출하여 gauge를 업데이트하고
     * 메시지 처리 과정에서 counter/timer를 기록합니다.
     */
    class Binder(registry: MeterRegistry, queueName: String) {

        private val queueDepthValue = AtomicLong(0)
        private val inflightValue = AtomicLong(0)
        private val concurrentValue = AtomicLong(0)

        val success: Counter
        val failure: Counter
        val retry: Counter
        val dlq: Counter
        val waitDuration: Timer

        init {
            Gauge.builder("pgmq.queue.depth") { queueDepthValue.get().toDouble() }
                .description("Current PGMQ queue depth (updated per poll cycle)")
                .tag("queue", queueName)
                .register(registry)

            Gauge.builder("pgmq.worker.inflight") { inflightValue.get().toDouble() }
                .description("Messages in-flight: read but not yet completed")
                .tag("queue", queueName)
                .register(registry)

            Gauge.builder("pgmq.worker.concurrent") { concurrentValue.get().toDouble() }
                .description("Messages currently being processed (= active virtual thread count)")
                .tag("queue", queueName)
                .register(registry)

            success = Counter.builder("pgmq.worker.success.total")
                .description("Total successfully processed messages")
                .tag("queue", queueName)
                .register(registry)

            failure = Counter.builder("pgmq.worker.failure.total")
                .description("Total failed message processing (includes retries and DLQ)")
                .tag("queue", queueName)
                .register(registry)

            retry = Counter.builder("pgmq.worker.retry.total")
                .description("Total messages sent for retry (readCount > 0)")
                .tag("queue", queueName)
                .register(registry)

            dlq = Counter.builder("pgmq.worker.dlq.total")
                .description("Total messages sent to DLQ (max retries exceeded)")
                .tag("queue", queueName)
                .register(registry)

            waitDuration = Timer.builder("pgmq.worker.wait.duration")
                .description("Time messages waited in queue before processing started")
                .tag("queue", queueName)
                .publishPercentileHistogram()
                .register(registry)
        }

        /** 폴링 사이클에서 큐 깊이 업데이트 */
        fun updateQueueDepth(depth: Long) {
            queueDepthValue.set(depth)
        }

        /** 메시지 읽음 → in-flight 증가 */
        fun inflightIncrement() {
            inflightValue.incrementAndGet()
        }

        /** 메시지 처리 완료 → in-flight 감소 */
        fun inflightDecrement() {
            inflightValue.decrementAndGet()
        }

        /** 처리 시작 → concurrent 증가 */
        fun concurrentIncrement() {
            concurrentValue.incrementAndGet()
        }

        /** 처리 종료 → concurrent 감소 */
        fun concurrentDecrement() {
            concurrentValue.decrementAndGet()
        }

        /** 큐 대기 시간 기록 (enqueuedAt ~ now). 음수면 무시. */
        fun recordWaitDuration(enqueuedAt: Instant) {
            val waited = Duration.between(enqueuedAt, Instant.now())
            if (!waited.isNegative) waitDuration.record(waited)
        }

        /** 현재 in-flight 수 (테스트/디버깅용) */
        fun getInflight(): Long = inflightValue.get()

        /** 현재 concurrent 수 (테스트/디버깅용) */
        fun getConcurrent(): Long = concurrentValue.get()
    }
}

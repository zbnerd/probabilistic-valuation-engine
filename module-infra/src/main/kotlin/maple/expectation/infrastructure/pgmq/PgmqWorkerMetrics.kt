package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.*
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * PGMQ Worker 공통 메트릭 (Phase 0-5)
 *
 * <h3>Metrics Exposed</h3>
 * <ul>
 *   <li>Counter: pgmq_worker_processed_total, pgmq_worker_failed_total
 *   <li>Timer: pgmq_worker_processing_duration
 *   <li>Gauge: pgmq_worker_queue_length
 * </ul>
 *
 * <p>각 Worker는 `forQueue()`로 큐별 메트릭 인스턴스를 생성하여 사용.
 *
 * @see PgmqWorker
 */
@Component
class PgmqWorkerMetrics(private val registry: MeterRegistry) {

    /**
     * 큐별 메트릭 인스턴스 팩토리
     *
     * @param queueName 큐 이름
     * @return 큐 전용 메트릭 바인딩
     */
    fun forQueue(queueName: String): QueueMetrics = QueueMetrics(registry, queueName)

    /** 큐별 메트릭 바인딩 */
    class QueueMetrics(private val registry: MeterRegistry, private val queueName: String) {

        private val processedCounter: Counter = Counter.builder("pgmq_worker_processed_total")
            .description("Total number of messages processed")
            .tag("queue", queueName)
            .register(registry)

        private val failedCounter: Counter = Counter.builder("pgmq_worker_failed_total")
            .description("Total number of messages that failed processing")
            .tag("queue", queueName)
            .register(registry)

        private val processingTimer: Timer = Timer.builder("pgmq_worker_processing_duration")
            .description("Message processing time distribution")
            .tag("queue", queueName)
            .register(registry)

        private val queueLengthGauge: AtomicLong = AtomicLong(0)

        init {
            Gauge.builder("pgmq_worker_queue_length") { queueLengthGauge.get().toDouble() }
                .description("Current queue length")
                .tag("queue", queueName)
                .register(registry)
        }

        /** 처리 성공 카운터 증가 */
        fun incrementProcessed() {
            processedCounter.increment()
        }

        /** 처리 실패 카운터 증가 */
        fun incrementFailed() {
            failedCounter.increment()
        }

        /** 큐 길이 Gauge 업데이트 */
        fun setQueueLength(length: Long) {
            queueLengthGauge.set(length)
        }

        /** 처리 시간 기록 */
        fun recordProcessingDuration(task: Runnable) {
            processingTimer.record(task)
        }
    }
}

package maple.expectation.infrastructure.nexon.pgmq

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicLong
import maple.expectation.core.port.out.NexonApiOutboxMetricsPort
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * PGMQ 기반 Nexon API 재시도 메트릭
 *
 * <h3>기존 NexonApiOutboxMetrics 대체</h3>
 * <ul>
 *   <li>Prometheus 메트릭 이름 유지 (nexon_api_outbox.*)
 *   <li>PGMQ queue_length 기반 pending count
 * </ul>
 */
@Component
class NexonApiPgmqMetrics(
    private val registry: MeterRegistry,
    private val pgmqClient: PgmqClient,
) : NexonApiOutboxMetricsPort {

    companion object {
        private val log = LoggerFactory.getLogger(NexonApiPgmqMetrics::class.java)
        private const val QUEUE_NAME = "nexon_retry_queue"
    }

    private lateinit var processedCounter: Counter
    private lateinit var failedCounter: Counter
    private lateinit var dlqCounter: Counter
    private lateinit var dlqFileBackupCounter: Counter
    private lateinit var dlqCriticalFailureCounter: Counter
    private lateinit var integrityFailureCounter: Counter
    private lateinit var pollFailureCounter: Counter
    private lateinit var apiCallSuccessCounter: Counter
    private lateinit var apiCallRetryCounter: Counter

    private val pendingCount = AtomicLong(0)

    @PostConstruct
    fun init() {
        processedCounter = registry.counter("nexon_api_outbox.processed.total")
        failedCounter = registry.counter("nexon_api_outbox.failed.total")
        dlqCounter = registry.counter("nexon_api_outbox.dlq.total")
        dlqFileBackupCounter = registry.counter("nexon_api_outbox.dlq.file_backup.total")
        dlqCriticalFailureCounter = registry.counter("nexon_api_outbox.dlq.critical_failure.total")
        integrityFailureCounter = registry.counter("nexon_api_outbox.integrity.failure.total")
        pollFailureCounter = registry.counter("nexon_api_outbox.poll.failure.total")
        apiCallSuccessCounter = registry.counter("nexon_api_outbox.api_call.success.total")
        apiCallRetryCounter = registry.counter("nexon_api_outbox.api_call.retry.total")

        registry.gauge("nexon_api_outbox.pending.count", pendingCount)
    }

    fun incrementProcessed() { processedCounter.increment() }
    fun incrementFailed() { failedCounter.increment() }
    fun incrementDlq() { dlqCounter.increment() }
    fun incrementDlqFileBackup() { dlqFileBackupCounter.increment() }
    fun incrementDlqCriticalFailure() { dlqCriticalFailureCounter.increment() }
    fun incrementIntegrityFailure() { integrityFailureCounter.increment() }
    fun incrementPollFailure() { pollFailureCounter.increment() }
    fun incrementApiCallSuccess() { apiCallSuccessCounter.increment() }
    fun incrementApiCallRetry() { apiCallRetryCounter.increment() }

    override fun updatePendingCount() {
        val count = pgmqClient.queueLength(QUEUE_NAME)
        pendingCount.set(count)
    }
}

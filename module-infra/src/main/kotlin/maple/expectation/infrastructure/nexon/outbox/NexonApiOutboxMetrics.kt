package maple.expectation.infrastructure.nexon.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import maple.expectation.core.port.out.NexonApiOutboxMetricsPort
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicLong

/**
 * Nexon API Outbox 메트릭 관리 (N19)
 *
 * <h3>SRP 준수</h3>
 *
 * <p>NexonApiOutboxProcessor에서 분리하여 단일 책임 원칙 준수
 *
 * <h3>CLAUDE.md §17 준수</h3>
 *
 * <ul>
 *   <li>소문자 점 표기법 (nexon_api_outbox.*)
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)
 * </ul>
 *
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 */
@Component
class NexonApiOutboxMetrics(
    private val registry: MeterRegistry,
    private val repository: NexonApiOutboxRepository,
) : NexonApiOutboxMetricsPort {

    // Counters (Thread-safe) - initialized in @PostConstruct
    private lateinit var processedCounter: Counter
    private lateinit var failedCounter: Counter
    private lateinit var dlqCounter: Counter
    private lateinit var dlqMovedCounter: Counter
    private lateinit var dlqFileBackupCounter: Counter
    private lateinit var dlqCriticalFailureCounter: Counter
    private lateinit var integrityFailureCounter: Counter
    private lateinit var stalledRecoveredCounter: Counter
    private lateinit var pollFailureCounter: Counter
    private lateinit var apiCallSuccessCounter: Counter
    private lateinit var apiCallRetryCounter: Counter

    // Gauge backing field
    private val pendingCount = AtomicLong(0)

    /**
     * 메트릭 초기화 (1회만 실행)
     *
     * <p>gauge 중복 등록 방지
     */
    @PostConstruct
    fun init() {
        // Counters 초기화
        processedCounter = registry.counter("nexon_api_outbox.processed.total")
        failedCounter = registry.counter("nexon_api_outbox.failed.total")
        dlqCounter = registry.counter("nexon_api_outbox.dlq.total")
        dlqMovedCounter = registry.counter("nexon_api_outbox.dlq.moved.total")
        dlqFileBackupCounter = registry.counter("nexon_api_outbox.dlq.file_backup.total")
        dlqCriticalFailureCounter = registry.counter("nexon_api_outbox.dlq.critical_failure.total")
        integrityFailureCounter = registry.counter("nexon_api_outbox.integrity.failure.total")
        stalledRecoveredCounter = registry.counter("nexon_api_outbox.stalled.recovered.total")
        pollFailureCounter = registry.counter("nexon_api_outbox.poll.failure.total")
        apiCallSuccessCounter = registry.counter("nexon_api_outbox.api_call.success.total")
        apiCallRetryCounter = registry.counter("nexon_api_outbox.api_call.retry.total")

        // Gauge 초기화 (1회만)
        registry.gauge("nexon_api_outbox.pending.count", pendingCount)
    }

    // ========== Counter Methods ==========

    fun incrementProcessed() {
        processedCounter.increment()
    }

    fun incrementFailed() {
        failedCounter.increment()
    }

    fun incrementDlq() {
        dlqCounter.increment()
    }

    fun incrementIntegrityFailure() {
        integrityFailureCounter.increment()
    }

    fun incrementStalledRecovered(count: Int) {
        stalledRecoveredCounter.increment(count.toDouble())
    }

    fun incrementPollFailure() {
        pollFailureCounter.increment()
    }

    fun incrementApiCallSuccess() {
        apiCallSuccessCounter.increment()
    }

    fun incrementApiCallRetry() {
        apiCallRetryCounter.increment()
    }

    fun incrementDlqMoved() {
        dlqMovedCounter.increment()
    }

    fun incrementDlqFileBackup() {
        dlqFileBackupCounter.increment()
    }

    fun incrementDlqCriticalFailure() {
        dlqCriticalFailureCounter.increment()
    }

    // ========== Gauge Methods ==========

    /**
     * Pending 항목 수 갱신
     *
     * <p>스케줄러에서 주기적으로 호출
     */
    @Transactional("transactionManager", readOnly = true)
    override fun updatePendingCount() {
        val count = repository.countByStatusIn(listOf(OutboxStatus.PENDING, OutboxStatus.FAILED) as java.util.List<OutboxStatus>)
        pendingCount.set(count)
    }
}

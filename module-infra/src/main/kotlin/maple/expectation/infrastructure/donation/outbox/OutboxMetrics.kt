package maple.expectation.infrastructure.donation.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import maple.expectation.core.port.out.OutboxMetricsPort
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicLong

/**
 * Outbox 메트릭 관리 (Issue #80)
 *
 * <h3>SRP 준수</h3>
 * OutboxProcessor에서 분리하여 단일 책임 원칙 준수
 *
 * <h3>CLAUDE.md §17 준수</h3>
 * - 소문자 점 표기법
 * - @PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)
 *
 * @see OutboxProcessor
 */
@Component
class OutboxMetrics(
    private val registry: MeterRegistry,
    private val repository: DonationOutboxRepository,
    private val properties: OutboxProperties
) : OutboxMetricsPort {

    private val log = LoggerFactory.getLogger(javaClass)

    // Counters (Thread-safe)
    private lateinit var processedCounter: Counter
    private lateinit var failedCounter: Counter
    private lateinit var dlqCounter: Counter
    private lateinit var dlqReprocessedCounter: Counter
    private lateinit var dlqDiscardedCounter: Counter
    private lateinit var fileBackupCounter: Counter
    private lateinit var criticalCounter: Counter
    private lateinit var integrityFailureCounter: Counter
    private lateinit var stalledRecoveredCounter: Counter
    private lateinit var pollFailureCounter: Counter
    private lateinit var notificationSentCounter: Counter

    // Gauge backing fields
    private val pendingCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)

    /**
     * 메트릭 초기화 (1회만 실행)
     * Green 요구사항: gauge 중복 등록 방지
     */
    @PostConstruct
    fun init() {
        // Counters 초기화
        processedCounter = registry.counter("outbox.processed.total")
        failedCounter = registry.counter("outbox.failed.total")
        dlqCounter = registry.counter("outbox.dlq.total")
        dlqReprocessedCounter = registry.counter("outbox.dlq.reprocessed.total")
        dlqDiscardedCounter = registry.counter("outbox.dlq.discarded.total")
        fileBackupCounter = registry.counter("outbox.safety.file.total")
        criticalCounter = registry.counter("outbox.safety.critical.total")
        integrityFailureCounter = registry.counter("outbox.integrity.failure.total")
        stalledRecoveredCounter = registry.counter("outbox.stalled.recovered.total")
        pollFailureCounter = registry.counter("outbox.poll.failure.total")
        notificationSentCounter = registry.counter("outbox.notification.sent.total")

        // Gauge 초기화 (1회만)
        registry.gauge("outbox.pending.count", pendingCount)
        registry.gauge("outbox.size.total", totalCount)

        log.info("[OutboxMetrics] 메트릭 초기화 완료")
    }

    // ========== Counter Methods ==========

    fun incrementProcessed() = processedCounter.increment()

    fun incrementFailed() = failedCounter.increment()

    fun incrementDlq() = dlqCounter.increment()

    fun incrementDlqReprocessed() = dlqReprocessedCounter.increment()

    fun incrementDlqDiscarded() = dlqDiscardedCounter.increment()

    fun incrementFileBackup() = fileBackupCounter.increment()

    fun incrementCriticalFailure() = criticalCounter.increment()

    fun incrementIntegrityFailure() = integrityFailureCounter.increment()

    fun incrementStalledRecovered(count: Int) = stalledRecoveredCounter.increment(count.toDouble())

    fun incrementPollFailure() = pollFailureCounter.increment()

    fun incrementNotificationSent() = notificationSentCounter.increment()

    // ========== Gauge Methods ==========

    /**
     * Pending 항목 수 갱신
     * 스케줄러에서 주기적으로 호출
     */
    @Transactional("transactionManager", readOnly = true)
    override fun updatePendingCount() {
        val count = repository.countByStatusIn(listOf(OutboxStatus.PENDING, OutboxStatus.FAILED))
        pendingCount.set(count)
    }

    /**
     * Outbox 전체 크기 갱신 (모든 상태 포함)
     * Issue #N19: 처리 지연 감지용 메트릭
     * 스케줄러에서 주기적으로 호출 (30초)
     */
    override fun updateTotalCount() {
        val count = repository.count()
        totalCount.set(count)
    }

    /**
     * Outbox 상태 확인 (백로그 여부)
     * @return true: 백로그 상태 (처리 지연), false: 정상
     */
    fun isBacklogged(): Boolean = totalCount.get() > properties.sizeAlertThreshold

    /**
     * 현재 Outbox 크기 조회
     * @return 전체 Outbox 항목 수
     */
    override fun getCurrentSize(): Long = totalCount.get()
}

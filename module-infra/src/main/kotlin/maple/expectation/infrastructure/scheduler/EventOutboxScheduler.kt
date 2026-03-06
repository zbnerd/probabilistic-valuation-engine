package maple.expectation.infrastructure.scheduler

import maple.expectation.domain.v2.EventOutbox.EventOutboxStatus
import maple.expectation.infrastructure.config.EventOutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import maple.expectation.infrastructure.persistence.repository.EventOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Event Outbox Pattern 폴링 스케줄러
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)
 *   <li>monitorMetrics: 60초 (메트릭 갱신)
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)
 * </ul>
 *
 * <h3>LogicExecutor 사용</h3>
 * 모든 예외 처리는 LogicExecutor를 통해 처리 (try-catch 금지)
 *
 * @see maple.expectation.infrastructure.config.EventOutboxProperties
 * @see maple.expectation.infrastructure.metrics.EventOutboxMetrics
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.event-outbox.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class EventOutboxScheduler(
    private val eventOutboxRepository: EventOutboxRepository,
    private val metrics: EventOutboxMetrics,
    private val executor: LogicExecutor,
    private val properties: EventOutboxProperties
) {
    private val log = LoggerFactory.getLogger(EventOutboxScheduler::class.java)

    /**
     * Event Outbox 폴링 및 처리 (기본 10초)
     *
     * <p>PENDING 상태의 이벤트를 조회하여 처리 후 COMPLETED로 변경
     * 배치 크기는 EventOutboxProperties.batchSize로 설정
     */
    @Scheduled(fixedDelayString = "\${event-outbox.polling-interval:10s}")
    fun pollAndProcess() {
        executor.executeVoidJava(
            {
                val batchSize = properties.batchSize
                val pendingEvents = eventOutboxRepository.findPendingForProcessing(batchSize)

                if (pendingEvents.isNotEmpty()) {
                    log.info("[EventOutbox] Processing {} pending events", pendingEvents.size)
                    metrics.recordProcessingTime {
                        processEvents(pendingEvents)
                    }
                }
            },
            TaskContext.of("Scheduler", "EventOutbox.Poll")
        )
    }

    /**
     * 메트릭 모니터링 (기본 60초)
     *
     * <p>Pending, Processing 카운트를 갱신하여 Prometheus에 노출
     */
    @Scheduled(fixedDelayString = "\${event-outbox.monitoring-interval:60s}")
    fun monitorMetrics() {
        executor.executeVoidJava(
            {
                val pendingCount = eventOutboxRepository.countByStatus("PENDING")
                val processingCount = eventOutboxRepository.countByStatus("PROCESSING")

                metrics.setPendingCount(pendingCount)
                metrics.setProcessingCount(processingCount)

                if (log.isDebugEnabled) {
                    log.debug(
                        "[EventOutbox] Metrics updated - Pending: {}, Processing: {}",
                        pendingCount,
                        processingCount
                    )
                }
            },
            TaskContext.of("Scheduler", "EventOutbox.MonitorMetrics")
        )
    }

    /**
     * Stalled 상태 복구 (기본 5분)
     *
     * <p>JVM 크래시 등으로 PROCESSING 상태에서 멈춘 항목을 PENDING으로 복원
     * Staled 판정 기준: EventOutboxProperties.stalledThreshold
     */
    @Scheduled(fixedDelayString = "\${event-outbox.stalled-recovery-interval:5m}")
    fun recoverStalled() {
        executor.executeVoidJava(
            {
                val stalledThreshold = properties.stalledThreshold
                val thresholdTime = Instant.now().minus(stalledThreshold)

                val stalledEvents = eventOutboxRepository.findStalledEvents(thresholdTime)

                if (stalledEvents.isNotEmpty()) {
                    log.warn(
                        "[EventOutbox] Found {} stalled events, recovering...",
                        stalledEvents.size
                    )

                    val recoveredCount = recoverStalledEvents(stalledEvents)

                    metrics.incrementStalledRecovered(recoveredCount)
                    log.info("[EventOutbox] Recovered {} stalled events", recoveredCount)
                }
            },
            TaskContext.of("Scheduler", "EventOutbox.RecoverStalled")
        )
    }

    // ========== Private Helper Methods ==========

    /**
     * 이벤트 처리 (내부 로직)
     *
     * <p>실제 처리 로직은 EventOutboxProcessor에서 수행
     * 여기서는 메트릭만 업데이트
     */
    private fun processEvents(eventIds: List<Long>) {
        val completedCount = eventOutboxRepository.countByStatusIn(eventIds, listOf("COMPLETED"))
        val failedCount = eventOutboxRepository.countByStatusIn(eventIds, listOf("FAILED"))

        if (completedCount > 0) {
            metrics.incrementCompleted(completedCount.toInt())
        }

        if (failedCount > 0) {
            metrics.incrementFailed(failedCount.toInt())
        }
    }

    /**
     * Staled 이벤트 복구 (내부 로직)
     *
     * <p>PROCESSING -> PENDING으로 상태 변경 후 무결성 검증
     */
    private fun recoverStalledEvents(stalledEventIds: List<Long>): Int {
        val recoveredCount = eventOutboxRepository.resetStalledEvents(stalledEventIds)

        if (recoveredCount > 0) {
            log.info("[EventOutbox] Reset {} events from PROCESSING to PENDING", recoveredCount)
        }

        return recoveredCount
    }
}

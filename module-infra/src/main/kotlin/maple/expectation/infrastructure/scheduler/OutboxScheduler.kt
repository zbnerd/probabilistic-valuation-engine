package maple.expectation.infrastructure.scheduler

import maple.expectation.core.port.out.OutboxMetricsPort
import maple.expectation.core.port.out.OutboxProcessorPort
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Outbox 폴링 스케줄러 (ADR-005 이관)
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>pollAndProcess: 15초 (Pending -> Processing -> Completed)
 *   <li>monitorOutboxSize: 60초 (백로그 감지)
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)
 * </ul>
 *
 * @see OutboxProcessorPort
 * @see OutboxMetricsPort
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.outbox.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxScheduler(
    private val outboxProcessor: OutboxProcessorPort,
    private val outboxMetrics: OutboxMetricsPort,
    private val executor: LogicExecutor,
    private val properties: OutboxProperties,
) {
    private val log = LoggerFactory.getLogger(OutboxScheduler::class.java)

    /**
     * Outbox 폴링 및 처리 (15초)
     */
    @Scheduled(fixedDelay = 15000)
    fun pollAndProcess() {
        executor.executeVoidJava(
            {
                outboxProcessor.pollAndProcess()
                outboxMetrics.updatePendingCount()
            },
            TaskContext.of("Scheduler", "Outbox.Poll"),
        )
    }

    /**
     * Outbox 크기 모니터링 (60초)
     */
    @Scheduled(fixedDelay = 60000)
    fun monitorOutboxSize() {
        executor.executeVoidJava(
            {
                outboxMetrics.updateTotalCount()
                val currentSize = outboxMetrics.getCurrentSize()
                val threshold = properties.sizeAlertThreshold

                if (currentSize > threshold) {
                    log.warn("[Outbox] 백로그 감지: {}건 (임계값: {}건)", currentSize, threshold)
                }
            },
            TaskContext.of("Scheduler", "Outbox.MonitorSize"),
        )
    }

    /**
     * Stalled 상태 복구 (5분)
     */
    @Scheduled(fixedDelay = 300000)
    fun recoverStalled() {
        executor.executeVoidJava(
            { outboxProcessor.recoverStalled() },
            TaskContext.of("Scheduler", "Outbox.RecoverStalled"),
        )
    }
}

package maple.expectation.infrastructure.scheduler

import maple.expectation.core.port.out.NexonApiOutboxMetricsPort
import maple.expectation.core.port.out.NexonApiOutboxProcessorPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nexon API Outbox 폴링 스케줄러 (ADR-005 이관)
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)
 * </ul>
 *
 * @see NexonApiOutboxProcessorPort
 * @see NexonApiOutboxMetricsPort
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.nexon-api-outbox.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class NexonApiOutboxScheduler(
    private val outboxProcessor: NexonApiOutboxProcessorPort,
    private val outboxMetrics: NexonApiOutboxMetricsPort,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(NexonApiOutboxScheduler::class.java)

    /**
     * Nexon API Outbox 폴링 및 처리 (10초)
     */
    @Scheduled(fixedDelay = 10000)
    fun pollAndProcess() {
        executor.executeVoidJava(
            {
                // 메트릭: 처리 전 Pending 수
                outboxMetrics.updatePendingCount()

                // Outbox 폴링 및 처리
                outboxProcessor.pollAndProcess()

                // 메트릭: 처리 후 Pending 수
                outboxMetrics.updatePendingCount()
            },
            TaskContext.of("Scheduler", "NexonApiOutbox.Poll"),
        )
    }

    /**
     * Stalled 상태 복구 (5분)
     */
    @Scheduled(fixedDelay = 300000)
    fun recoverStalled() {
        executor.executeVoidJava(
            { outboxProcessor.recoverStalled() },
            TaskContext.of("Scheduler", "NexonApiOutbox.RecoverStalled"),
        )
    }
}

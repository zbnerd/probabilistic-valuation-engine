package maple.expectation.infrastructure.job

import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.outbox.compensating-scanner.enabled"], havingValue = "true", matchIfMissing = false)
class OutboxCompensatingScanner(
    private val jobPort: CalculationJobPort,
    private val outboxPort: OutboxEventPort,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    fun scan() {
        val context = TaskContext.of("OutboxCompensatingScanner", "Scan", "system")
        executor.executeVoid({
            val orphaned = jobPort.findCompletedJobsMissingOutboxEvents(50)
            if (orphaned.isEmpty()) return@executeVoid

            log.warn("Found {} orphaned completed jobs without outbox events", orphaned.size)
            for (jobId in orphaned) {
                val payload = """{"jobId":"$jobId","orphanRecovery":true}"""
                outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, payload)
                log.info("[jobId={}] Compensating: created outbox event", jobId)
            }
        }, context)
    }
}

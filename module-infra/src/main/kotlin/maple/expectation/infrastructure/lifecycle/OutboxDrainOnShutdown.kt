package maple.expectation.infrastructure.lifecycle

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.domain.v2.DonationOutbox
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
import maple.expectation.infrastructure.shutdown.ShutdownProperties
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.context.SmartLifecycle
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.Arrays

@Component
class OutboxDrainOnShutdown(
    private val outboxRepository: DonationOutboxRepository,
    private val executor: LogicExecutor,
    private val properties: ShutdownProperties,
    private val outboxProcessor: OutboxShutdownProcessor,
    private val meterRegistry: MeterRegistry
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(OutboxDrainOnShutdown::class.java)
    private val shutdownDrainTimer: Timer = Timer.builder("shutdown.outbox.drain.duration")
        .description("Outbox Drain 소요 시간")
        .register(meterRegistry)
    private val drainSuccessCounter: Counter = Counter.builder("shutdown.outbox.drain.tasks")
        .tag("status", "success")
        .description("Drain 성공 횟수")
        .register(meterRegistry)
    private val drainFailureCounter: Counter = Counter.builder("shutdown.outbox.drain.tasks")
        .tag("status", "failure")
        .description("Drain 실패 횟수")
        .register(meterRegistry)

    @Volatile
    private var running = false

    override fun start() {
        this.running = true
        logger.debug("[OutboxDrain] Started")
    }

    override fun stop() {
        val context = TaskContext.of("OutboxDrain", "Main")
        val startNanos = System.nanoTime()

        executor.executeVoidJava(
            {
                try {
                    logger.warn("[OutboxDrain] ========== Shutdown 시작 ==========")
                } finally {
                    this.running = false
                    shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
                }
            },
            context
        )

        var totalProcessed = 0
        var totalFailed = 0
        val batchSize = properties.batchSize

        while (true) {
            val pendingEntries = fetchPendingBatch(batchSize)

            if (pendingEntries.isEmpty()) {
                logger.info("[OutboxDrain] Batch #{}: 처리 대기 항목 없음", batchSize)
                break
            }

            var batchCount = 0
            while (pendingEntries.isNotEmpty()) {
                batchCount++
                val result = processBatch(pendingEntries)
                totalProcessed += result.processed
                totalFailed += result.failed

                if (totalFailed > 0) {
                    break
                }
            }

            logger.info("[OutboxDrain] Batch #{}: {}건 처리, {}건 실패", batchCount, totalProcessed, totalFailed)
        }

        val remainingCount = countRemainingEntries()
        if (remainingCount == 0L) {
            logger.info("[OutboxDrain] 모든 Outbox 항목 처리 완료")
        } else {
            logger.warn("[OutboxDrain] {}건 남음", remainingCount)
        }

        drainSuccessCounter.increment(totalProcessed.toDouble())
        drainFailureCounter.increment(totalFailed.toDouble())
        shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
        this.running = false
        logger.warn("[OutboxDrain] ========== Shutdown 완료 ==========")
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Integer.MAX_VALUE

    override fun isAutoStartup(): Boolean = true

    private fun countRemainingEntries(): Long {
        return executor.executeOrDefault(
            { outboxRepository.countByStatusIn(listOf(DonationOutbox.OutboxStatus.PENDING)) },
            0L,
            TaskContext.of("OutboxDrain", "CountRemaining")
        )
    }

    private fun fetchPendingBatch(batchSize: Int): List<DonationOutbox> {
        return executor.executeOrDefault(
            {
                outboxRepository.findPendingWithLock(
                    listOf(DonationOutbox.OutboxStatus.PENDING),
                    LocalDateTime.now(),
                    PageRequest.of(0, batchSize)
                )
            },
            emptyList(),
            TaskContext.of("OutboxDrain", "FetchBatch")
        )
    }

    private fun processBatch(entries: List<DonationOutbox>): OutboxShutdownProcessor.DrainResult {
        return executor.executeOrDefault(
            { outboxProcessor.processBatch(entries) },
            OutboxShutdownProcessor.DrainResult(0, 0),
            TaskContext.of("OutboxDrain", "ProcessBatch")
        )
    }
}

package maple.expectation.infrastructure.lifecycle

import maple.expectation.domain.v2.DonationOutbox
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
import maple.expectation.infrastructure.shutdown.ShutdownProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class OutboxShutdownProcessor(
    private val outboxRepository: DonationOutboxRepository,
    private val executor: LogicExecutor,
    private val properties: ShutdownProperties
) {
    private val logger = LoggerFactory.getLogger(OutboxShutdownProcessor::class.java)

    data class DrainResult(val processed: Int, val failed: Int)

    fun processBatch(entries: List<DonationOutbox>): DrainResult {
        val processed = AtomicInteger(0)
        val failed = AtomicInteger(0)

        for (entry in entries) {
            val success = executor.executeOrDefault(
                { processEntry(entry) },
                false,
                TaskContext.of("OutboxShutdown", "ProcessEntry", entry.requestId)
            )

            if (success) {
                processed.incrementAndGet()
            } else {
                failed.incrementAndGet()
            }
        }

        return DrainResult(processed.get(), failed.get())
    }

    private fun processEntry(entry: DonationOutbox): Boolean {
        if (!entry.verifyIntegrity()) {
            logger.warn("[OutboxShutdown] 무결성 검증 실패 -> DEAD_LETTER: {}", entry.requestId)
            entry.forceDeadLetter()
            outboxRepository.save(entry)
            return false
        }

        return try {
            entry.markProcessing(properties.instanceId)
            entry.markCompleted()
            outboxRepository.save(entry)
            logger.info("[OutboxShutdown] 처리 완료: {}", entry.requestId)
            true
        } catch (e: Exception) {
            logger.error("[OutboxShutdown] 처리 실패: {}", entry.requestId, e)
            entry.markFailed(e.message ?: "Unknown error")
            outboxRepository.save(entry)

            if (entry.shouldMoveToDlq()) {
                entry.forceDeadLetter()
                outboxRepository.save(entry)
            }
            false
        }
    }
}

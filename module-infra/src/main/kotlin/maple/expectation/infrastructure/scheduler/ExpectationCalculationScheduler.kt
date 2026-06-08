package maple.expectation.infrastructure.scheduler

import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.port.out.QueueWriterPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * V5 CQRS: Expectation Calculation Scheduler (ADR-005 이관)
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.expectation-calculation.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class ExpectationCalculationScheduler(
    private val queueWriter: QueueWriterPort,
    private val gameCharacterRepository: GameCharacterRepository,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(ExpectationCalculationScheduler::class.java)

    @Value("\${scheduler.expectation-calculation.batch-size:100}")
    private var batchSize: Int = 100

    @Scheduled(fixedDelayString = "\${scheduler.expectation-calculation.fixed-delay-ms:3600000}")
    fun refreshAllUsers() {
        val context = TaskContext.of("Scheduler", "ExpectationCalculation.RefreshAll")

        executor.executeVoidJava(
            {
                log.info("[ExpectationCalculation] Starting full user refresh")
                var processedCount = 0
                var skippedCount = 0

                val pageSize = 100
                var page = 0
                var hasMore = true

                while (hasMore) {
                    val currentPage = page
                    hasMore = executor.executeOrDefault(
                        {
                            val pageRequest: PageRequest = PageRequest.of(currentPage, pageSize)
                            val characterPage: Page<GameCharacter> =
                                gameCharacterRepository.findAll(pageRequest)

                            for (character in characterPage.content) {
                                addTaskForUser(character.userIgn.value)
                            }

                            characterPage.hasNext
                        },
                        false,
                        context,
                    )

                    if (hasMore) {
                        page++
                        processedCount += pageSize

                        if (processedCount % batchSize == 0) {
                            log.info(
                                "[ExpectationCalculation] Processed {} users, skipped {} (queue full)",
                                processedCount,
                                skippedCount,
                            )
                        }
                    }
                }

                log.info(
                    "[ExpectationCalculation] Full refresh completed: processed={}, skipped={}, queueSize={}",
                    processedCount,
                    skippedCount,
                    queueWriter.size(),
                )
            },
            context,
        )
    }

    private fun addTaskForUser(userIgn: String): Boolean = queueWriter.addLowPriorityTask(userIgn)
}

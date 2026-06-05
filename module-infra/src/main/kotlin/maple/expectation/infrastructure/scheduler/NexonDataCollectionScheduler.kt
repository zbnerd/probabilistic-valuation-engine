package maple.expectation.infrastructure.scheduler

import maple.expectation.core.port.out.NexonDataCollectorPort
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nexon API 데이터 수집 스케줄러 (ADR-005 이관)
 *
 * @see NexonDataCollectorPort
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.nexon-data-collection.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class NexonDataCollectionScheduler(
    private val dataCollector: NexonDataCollectorPort,
    private val gameCharacterRepository: GameCharacterRepository,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(NexonDataCollectionScheduler::class.java)

    @Scheduled(
        fixedRateString = "\${scheduler.nexon-data-collection.rate:600000}",
        initialDelayString = "\${scheduler.nexon-data-collection.initial-delay:30000}",
    )
    fun collectNexonData() {
        executor.executeVoidJava(
            { processAllCharacters() },
            TaskContext.of("Scheduler", "NexonDataCollection"),
        )
    }

    private fun processAllCharacters() {
        log.info("[NexonDataCollectionScheduler] Starting scheduled data collection")

        val charactersToProcess = gameCharacterRepository.findActiveCharacters().stream()
            .limit(100)
            .toList()

        if (charactersToProcess.isEmpty()) {
            log.info("[NexonDataCollectionScheduler] No characters found in database")
            return
        }

        log.info("[NexonDataCollectionScheduler] Processing {} characters", charactersToProcess.size)

        var successCount = 0
        var failureCount = 0

        for (character in charactersToProcess) {
            val ocid = character.characterId.value
            val userIgn = character.userIgn.value

            val success = executor.executeOrDefault(
                {
                    dataCollector.fetchAndPublish(ocid)
                    true
                },
                false,
                TaskContext.of("Scheduler", "CollectCharacter", userIgn),
            )

            if (success) {
                successCount++
                log.debug(
                    "[NexonDataCollectionScheduler] Successfully collected data for: {} (ocid={})",
                    userIgn,
                    ocid,
                )
            } else {
                failureCount++
                log.error(
                    "[NexonDataCollectionScheduler] Failed to collect data for: {} (ocid={})",
                    userIgn,
                    ocid,
                )
            }
        }

        log.info(
            "[NexonDataCollectionScheduler] Data collection completed: success={}, failure={}",
            successCount,
            failureCount,
        )
    }
}

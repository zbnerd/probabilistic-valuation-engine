package maple.expectation.infrastructure.bulk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.LocalDateTime
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CheckpointManager(
    @Value("\${bulk.checkpoint.path:./checkpoint.json}")
    private val checkpointPath: String,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(CheckpointManager::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    data class Checkpoint(
        val timestamp: LocalDateTime,
        val completedIgnSet: Set<String>,
        val lastProcessedIndex: Int,
        val totalCharacters: Int,
    )

    /**
     * Save current progress to checkpoint file
     */
    fun save(completed: Set<String>, lastIndex: Int, total: Int) {
        executor.executeVoid({
            val checkpoint = Checkpoint(
                timestamp = LocalDateTime.now(),
                completedIgnSet = completed,
                lastProcessedIndex = lastIndex,
                totalCharacters = total,
            )

            val file = File(checkpointPath)
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, checkpoint)

            log.info(
                "Checkpoint saved: {} characters processed (index: {} of {})",
                completed.size,
                lastIndex,
                total,
            )
        }, TaskContext.of("CheckpointManager", "save"))
    }

    /**
     * Load checkpoint from file if exists
     * @return Checkpoint or null if file doesn't exist
     */
    fun load(): Checkpoint? {
        return executor.executeOrDefault({
            val file = File(checkpointPath)

            if (!file.exists()) {
                log.info("No checkpoint file found at {}", checkpointPath)
                return@executeOrDefault null
            }

            val checkpoint = mapper.readValue<Checkpoint>(file)

            log.info(
                "Checkpoint loaded: {} characters processed (index: {} of {}), timestamp: {}",
                checkpoint.completedIgnSet.size,
                checkpoint.lastProcessedIndex,
                checkpoint.totalCharacters,
                checkpoint.timestamp,
            )

            checkpoint
        }, null, TaskContext.of("CheckpointManager", "load"))
    }

    /**
     * Clear checkpoint file
     */
    fun clear() {
        executor.executeVoid({
            val file = File(checkpointPath)

            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    log.info("Checkpoint file deleted: {}", checkpointPath)
                } else {
                    log.warn("Failed to delete checkpoint file: {}", checkpointPath)
                }
            } else {
                log.info("No checkpoint file to delete at {}", checkpointPath)
            }
        }, TaskContext.of("CheckpointManager", "clear"))
    }
}

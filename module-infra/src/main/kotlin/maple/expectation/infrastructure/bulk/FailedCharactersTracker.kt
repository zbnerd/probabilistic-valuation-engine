package maple.expectation.infrastructure.bulk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Failed Characters Tracker for Issue #611
 *
 * <p>Tracks failed character loads during bulk operations for retry functionality.
 * Uses in-memory queue with periodic CSV persistence for durability.
 *
 * <h3>CSV Format:</h3>
 * <pre>
 * userIgn,errorType,timestamp,retryCount
 * </pre>
 *
 * <h3>Architecture Decision:</h3>
 * <ul>
 *   <li>In-memory queue for fast operations (ConcurrentLinkedQueue)</li>
 *   <li>CSV persistence for durability across restarts</li>
 *   <li>Uses LogicExecutor for all operations (CLAUDE.md compliance)</li>
 *   <li>Thread-safe operations using concurrent collections</li>
 * </ul>
 *
 * <h3>CLAUDE.md Section 1 Compliance:</h3>
 * <ul>
 *   <li>Uses LogicExecutor.executeOrDefault() for exception handling</li>
 *   <li>No raw try-catch blocks in business logic</li>
 * </ul>
 *
 * @property failedPath Path to failed records CSV file (default: ./failed.csv)
 * @property executor LogicExecutor for unified exception handling
 */
@Component
class FailedCharactersTracker(
    @Value("\${bulk.failed.path:./failed.csv}")
    private val failedPath: String,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(FailedCharactersTracker::class.java)

    private val failedQueue = ConcurrentLinkedQueue<FailedEntry>()
    private val ignIndex = ConcurrentHashMap<String, FailedEntry>()

    private val csvFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Represents a failed character load entry
     *
     * @property userIgn The in-game name that failed to load
     * @property errorType Type of error (e.g., "NETWORK_ERROR", "NOT_FOUND", "TIMEOUT")
     * @property timestamp When the failure occurred
     * @property retryCount Number of retry attempts made
     */
    data class FailedEntry(
        val userIgn: String,
        val errorType: String,
        val timestamp: LocalDateTime,
        val retryCount: Int,
    )

    /**
     * Record a failed character load
     *
     * <p>Adds the entry to in-memory queue and updates index.
     * Use save() to persist to CSV.
     *
     * @param entry The failed entry to record
     */
    fun record(entry: FailedEntry) = executor.executeVoid(
        { recordInternal(entry) },
        TaskContext.of("FailedCharactersTracker", "record", entry.userIgn),
    )

    private fun recordInternal(entry: FailedEntry) {
        failedQueue.offer(entry)
        ignIndex[entry.userIgn] = entry

        if (log.isDebugEnabled) {
            log.debug(
                "[FailedCharactersTracker] Recorded failed entry: IGN={}, Error={}, Retries={}",
                entry.userIgn,
                entry.errorType,
                entry.retryCount,
            )
        }
    }

    /**
     * Persist all in-memory failed entries to CSV file
     *
     * <p>Appends to existing file or creates new file if not exists.
     * Uses atomic file operations for consistency.
     */
    fun save() = executor.executeVoid(
        { saveInternal() },
        TaskContext.of("FailedCharactersTracker", "save"),
    )

    private fun saveInternal() {
        if (failedQueue.isEmpty()) {
            log.debug("[FailedCharactersTracker] No failed entries to save")
            return
        }

        val path = Path.of(failedPath)
        createParentDirectories(path)

        val csvContent = buildCsvContent()

        Files.writeString(
            path,
            csvContent,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        log.info("[FailedCharactersTracker] Saved {} failed entries to {}", failedQueue.size, failedPath)
    }

    /**
     * Load failed entries from CSV file
     *
     * <p>Replaces in-memory queue with contents from file.
     * Returns empty list if file doesn't exist.
     *
     * @return List of loaded failed entries
     */
    fun load(): List<FailedEntry> = executor.executeOrDefault(
        { loadInternal() },
        emptyList(),
        TaskContext.of("FailedCharactersTracker", "load"),
    )

    private fun loadInternal(): List<FailedEntry> {
        val path = Path.of(failedPath)

        if (!Files.exists(path)) {
            log.info("[FailedCharactersTracker] Failed file not found: {}", failedPath)
            return emptyList()
        }

        val lines = Files.readAllLines(path)
        val entries = parseCsvLines(lines)

        // Update in-memory structures
        failedQueue.clear()
        ignIndex.clear()
        entries.forEach { entry ->
            failedQueue.offer(entry)
            ignIndex[entry.userIgn] = entry
        }

        log.info("[FailedCharactersTracker] Loaded {} failed entries from {}", entries.size, failedPath)
        return entries
    }

    /**
     * Get set of failed IGNs for retry functionality
     *
     * <p>Returns unique set of in-game names that failed to load.
     * Used by BulkLoaderService to filter out already-failed characters.
     *
     * @return Set of failed IGNs
     */
    fun getFailedIgnSet(): Set<String> = executor.executeOrDefault(
        { ignIndex.keys.toSet() },
        emptySet(),
        TaskContext.of("FailedCharactersTracker", "getFailedIgnSet"),
    )

    /**
     * Get current size of failed entries queue
     *
     * @return Number of failed entries in memory
     */
    fun size(): Int = failedQueue.size

    /**
     * Clear all failed entries from memory
     *
     * <p>Does not delete the CSV file.
     */
    fun clear() = executor.executeVoid(
        {
            failedQueue.clear()
            ignIndex.clear()
        },
        TaskContext.of("FailedCharactersTracker", "clear"),
    )

    private fun createParentDirectories(path: Path) {
        if (path.parent != null && Files.notExists(path.parent)) {
            Files.createDirectories(path.parent)
        }
    }

    private fun buildCsvContent(): String {
        val header = "userIgn,errorType,timestamp,retryCount\n"
        val rows = failedQueue.joinToString("\n") { entry ->
            "${entry.userIgn},${entry.errorType},${entry.timestamp.format(csvFormatter)},${entry.retryCount}"
        }
        return header + rows
    }

    private fun parseCsvLines(lines: List<String>): List<FailedEntry> {
        if (lines.isEmpty()) return emptyList()

        val headerIndex = lines.indexOfFirst { it.startsWith("userIgn,") }
        val dataLines = if (headerIndex >= 0) {
            lines.drop(headerIndex + 1)
        } else {
            lines
        }

        return dataLines
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseCsvLine(line) }
    }

    private fun parseCsvLine(line: String): FailedEntry? = executor.executeOrDefault(
        {
            val parts = line.split(",")
            if (parts.size != 4) {
                log.warn("[FailedCharactersTracker] Invalid CSV line: {}", line)
                return@executeOrDefault null
            }

            val timestamp = LocalDateTime.parse(parts[2], csvFormatter)
            FailedEntry(
                userIgn = parts[0],
                errorType = parts[1],
                timestamp = timestamp,
                retryCount = parts[3].toIntOrNull() ?: 0,
            )
        },
        null,
        TaskContext.of("FailedCharactersTracker", "parseCsvLine"),
    )
}

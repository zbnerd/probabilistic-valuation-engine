package maple.expectation.infrastructure.bulk

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import maple.expectation.core.port.out.CacheWarmupPort
import maple.expectation.infrastructure.buffer.ExpectationWriteBackBuffer
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheStrategy
import maple.expectation.infrastructure.config.BulkLoadProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Bulk Loader Service for Issue #611
 *
 * Orchestrates bulk loading of character data from CSV with checkpointing,
 * progress tracking, failed character retry, and adaptive throttling.
 *
 * Architecture:
 * - Semaphore-based concurrency control (100 permits by default)
 * - CheckpointManager for resume capability
 * - ProgressLogger for real-time progress and ETA
 * - FailedCharactersTracker for retry functionality
 * - AdaptiveThrottler for dynamic rate limiting based on API responses
 * - LockStrategy for distributed locking
 * - Backpressure via ExpectationWriteBackBuffer.getPendingCount()
 *
 * CSV Format:
 * userIgn
 * CharacterName1
 * CharacterName2
 * ...
 */
@Service
class BulkLoaderService(
    private val properties: BulkLoadProperties,
    private val checkpointManager: CheckpointManager,
    private val progressLogger: ProgressLogger,
    private val failedTracker: FailedCharactersTracker,
    private val throttler: AdaptiveThrottler,
    private val cacheWarmupPort: CacheWarmupPort,
    private val writeBackBuffer: ExpectationWriteBackBuffer,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(BulkLoaderService::class.java)

    companion object {
        private const val LOCK_KEY = "bulk:load:lock:v2"
        private const val LOCK_WAIT_TIME_SECONDS = 5L
        private const val LOCK_LEASE_TIME_SECONDS = 3600L
        private const val BACKPRESSURE_THRESHOLD = 1000
        private const val PROGRESS_LOG_INTERVAL = 100
        private const val CHECKPOINT_INTERVAL = 500

        // Bounded executor for bulk loading - prevents ForkJoinPool exhaustion
        // 10 threads: matches semaphore permits to avoid Nexon API rate limiting
        private val BULK_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(10)
    }

    // State
    private val stopRequested = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val startTime = AtomicReference<Instant>(null)
    private val loadedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val totalCharacters = AtomicLong(0)

    /**
     * Result of bulk load operation
     */
    data class LoadResult(
        val totalCharacters: Int,
        val loadedCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val durationMs: Long,
        val error: String? = null,
    )

    /**
     * Load all characters from CSV file
     *
     * @param csvPath Path to CSV file (optional, defaults to properties.csvPath)
     * @param force Cache bypass flag
     * @return CompletableFuture with load result
     */
    fun loadAll(csvPath: String? = null, force: Boolean = false): CompletableFuture<LoadResult> = executor.execute(
        { loadAllInternal(csvPath, force) },
        TaskContext.of("BulkLoaderService", "loadAll", csvPath ?: properties.csvPath),
    )

    private fun loadAllInternal(csvPath: String?, force: Boolean): CompletableFuture<LoadResult> {
        val actualPath = csvPath ?: properties.csvPath
        val path = resolvePath(actualPath)

        return lockStrategy.executeWithLock(
            LOCK_KEY,
            LOCK_WAIT_TIME_SECONDS,
            LOCK_LEASE_TIME_SECONDS,
        ) {
            log.info("[BulkLoaderService] Starting bulk load from: {}", path)

            val start = Instant.now()
            startTime.set(start)
            stopRequested.set(false)
            isRunning.set(true)

            val ignList = readCsvFile(path)

            val total = ignList.size
            totalCharacters.set(total.toLong())
            log.info("[BulkLoaderService] Read {} characters from CSV", total)

            if (total == 0) {
                return@executeWithLock CompletableFuture.completedFuture(
                    LoadResult(0, 0, 0, 0, 0),
                )
            }

            processBatch(ignList, emptySet(), 0, total, start, force)
        }
    }

    /**
     * Resume bulk load from checkpoint
     */
    fun resume(): CompletableFuture<LoadResult> = executor.execute(
        { resumeInternal() },
        TaskContext.of("BulkLoaderService", "resume"),
    )

    private fun resumeInternal(): CompletableFuture<LoadResult> {
        return lockStrategy.executeWithLock(
            LOCK_KEY,
            LOCK_WAIT_TIME_SECONDS,
            LOCK_LEASE_TIME_SECONDS,
        ) {
            log.info("[BulkLoaderService] Resuming bulk load from checkpoint")

            val checkpoint = checkpointManager.load()
            if (checkpoint == null) {
                log.warn("[BulkLoaderService] No checkpoint found, starting fresh")
                return@executeWithLock loadAllInternal(null, false)
            }

            val path = resolvePath(properties.csvPath)
            val start = Instant.now()
            startTime.set(start)
            stopRequested.set(false)
            isRunning.set(true)

            val ignList = readCsvFile(path)
            val remainingList = ignList.drop(checkpoint.lastProcessedIndex + 1)
            val remainingCount = remainingList.size
            val skippedCount = checkpoint.completedIgnSet.size

            log.info(
                "[BulkLoaderService] Resuming from index {}: {} remaining, {} skipped",
                checkpoint.lastProcessedIndex,
                remainingCount,
                skippedCount,
            )

            if (remainingCount == 0) {
                return@executeWithLock CompletableFuture.completedFuture(
                    LoadResult(checkpoint.totalCharacters, skippedCount, 0, skippedCount, 0),
                )
            }

            processBatch(
                remainingList,
                checkpoint.completedIgnSet,
                checkpoint.lastProcessedIndex + 1,
                checkpoint.totalCharacters,
                start,
                false,
            )
        }
    }

    /**
     * Retry failed characters
     */
    fun retryFailed(): CompletableFuture<LoadResult> = executor.execute(
        { retryFailedInternal() },
        TaskContext.of("BulkLoaderService", "retryFailed"),
    )

    private fun retryFailedInternal(): CompletableFuture<LoadResult> {
        return lockStrategy.executeWithLock(
            "$LOCK_KEY:retry",
            LOCK_WAIT_TIME_SECONDS,
            LOCK_LEASE_TIME_SECONDS,
        ) {
            log.info("[BulkLoaderService] Retrying failed characters")

            val failedEntries = failedTracker.load()
            val failedIgnSet = failedEntries.map { it.userIgn }.toSet()

            if (failedIgnSet.isEmpty()) {
                log.info("[BulkLoaderService] No failed characters to retry")
                return@executeWithLock CompletableFuture.completedFuture(
                    LoadResult(0, 0, 0, 0, 0),
                )
            }

            log.info("[BulkLoaderService] Retrying {} failed characters", failedIgnSet.size)

            // Clear failed tracker for fresh start
            failedTracker.clear()

            val start = Instant.now()
            startTime.set(start)
            stopRequested.set(false)
            isRunning.set(true)

            val total = failedIgnSet.size
            totalCharacters.set(total.toLong())

            val ignList = failedIgnSet.toList()
            processBatch(ignList, emptySet(), 0, total, start, true)
        }
    }

    /**
     * Process a batch of characters with concurrency control
     */
    private fun processBatch(
        ignList: List<String>,
        completedIgnSet: Set<String>,
        startIndex: Int,
        total: Int,
        start: Instant,
        force: Boolean,
    ): CompletableFuture<LoadResult> {
        val loaded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val completedSet: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        completedIgnSet.forEach { completedSet.add(it) }

        val semaphore = Semaphore(properties.semaphore.permits)
        val futures = mutableListOf<CompletableFuture<Void>>()

        ignList.forEachIndexed { index, ign ->
            val future = CompletableFuture.runAsync({
                semaphore.acquire()
                try {
                    processCharacter(
                        ign, force, completedSet, loaded, failed, skipped,
                        total, start, startIndex, index,
                    )
                } finally {
                    semaphore.release()
                }
            }, BULK_EXECUTOR) // Use bounded executor instead of ForkJoinPool
            futures.add(future)
        }

        // Wait for all futures
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                val end = Instant.now()
                val durationMs = ChronoUnit.MILLIS.between(start, end)

                // Final checkpoint
                checkpointManager.save(
                    completedSet.toSet(),
                    startIndex + ignList.size - 1,
                    total,
                )

                // Final progress log
                logProgress(loaded.get(), total, failed.get(), start)

                // Save failed entries
                failedTracker.save()

                // Clear checkpoint on success
                checkpointManager.clear()

                LoadResult(
                    totalCharacters = total,
                    loadedCount = loaded.get(),
                    failedCount = failed.get(),
                    skippedCount = skipped.get(),
                    durationMs = durationMs,
                )
            }
    }

    private fun checkBackpressure() {
        val pendingCount = writeBackBuffer.getPendingCount()
        if (pendingCount > BACKPRESSURE_THRESHOLD) {
            log.debug("[BulkLoaderService] Backpressure detected: pending={}", pendingCount)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        }
    }

    /**
     * Process a single character with proper exception handling using LogicExecutor.
     */
    private fun processCharacter(
        ign: String,
        force: Boolean,
        completedSet: MutableSet<String>,
        loaded: AtomicInteger,
        failed: AtomicInteger,
        skipped: AtomicInteger,
        total: Int,
        start: Instant,
        startIndex: Int,
        index: Int,
    ) {
        if (shouldSkipProcessing(ign, completedSet)) {
            skipped.incrementAndGet()
            return
        }

        val singleStart = System.currentTimeMillis()
        val result: Unit? = PostgresL2CacheStrategy.withL2WritesDisabled {
            executor.executeOrCatch(
                task = {
                    cacheWarmupPort.warmup(ign, force)
                    completedSet.add(ign)
                    loaded.incrementAndGet()
                    loadedCount.incrementAndGet()
                    null
                },
                recovery = { e: Throwable ->
                    failed.incrementAndGet()
                    errorCount.incrementAndGet()
                    recordFailure(ign, e)
                    null
                },
                context = TaskContext.of("BulkLoaderService", "warmup", ign),
            )
        }
        val singleElapsed = System.currentTimeMillis() - singleStart
        log.info("[BulkLoaderService] Single character load: ign={} took {}ms", ign, singleElapsed)

        val currentLoaded = loaded.get()
        logCharacterProgress(currentLoaded, total, failed.get(), start)
        saveCheckpointIfNeeded(currentLoaded, startIndex, index, total, completedSet)

        val decision = throttler.onSuccess()
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(decision.delayMs))
    }

    private fun shouldSkipProcessing(ign: String, completedSet: MutableSet<String>): Boolean {
        if (stopRequested.get()) {
            return true
        }
        checkBackpressure()
        return completedSet.contains(ign)
    }

    private fun logCharacterProgress(loaded: Int, total: Int, failed: Int, start: Instant) {
        if (loaded % PROGRESS_LOG_INTERVAL == 0) {
            logProgress(loaded, total, failed, start)
        }
    }

    private fun saveCheckpointIfNeeded(loaded: Int, startIndex: Int, index: Int, total: Int, completedSet: MutableSet<String>) {
        if (loaded % CHECKPOINT_INTERVAL == 0) {
            val lastIndex = startIndex + index
            checkpointManager.save(
                completedSet.toSet(),
                lastIndex,
                total,
            )
        }
    }

    private fun recordFailure(userIgn: String, error: Throwable) {
        val errorType = classifyError(error)
        failedTracker.record(
            FailedCharactersTracker.FailedEntry(
                userIgn = userIgn,
                errorType = errorType,
                timestamp = java.time.LocalDateTime.now(),
                retryCount = 0,
            ),
        )
    }

    private fun classifyError(error: Throwable): String = when {
        error.message?.contains("429", ignoreCase = true) == true -> "RATE_LIMIT"
        error.message?.contains("timeout", ignoreCase = true) == true -> "TIMEOUT"
        error.message?.contains("not found", ignoreCase = true) == true -> "NOT_FOUND"
        else -> "UNKNOWN"
    }

    private fun logProgress(
        loadedCount: Int,
        total: Int,
        errors: Int,
        startTime: Instant,
    ) {
        val rate = progressLogger.calculateRate(loadedCount, startTime)
        val eta = progressLogger.calculateEta(loadedCount, total, startTime)

        progressLogger.logProgress(
            ProgressLogger.Progress(
                loaded = loadedCount,
                total = total,
                errors = errors,
                ratePerSecond = rate,
                etaMinutes = eta,
            ),
        )
    }

    private fun readCsvFile(path: Path): List<String> = executor.execute(
        {
            BufferedReader(InputStreamReader(Files.newInputStream(path))).use { reader ->
                reader.lines()
                    .filter { it.isNotBlank() }
                    .filter { !it.startsWith("userIgn") }
                    .map { it.trim() }
                    .toList()
            }
        },
        TaskContext.of("BulkLoaderService", "readCsvLines"),
    )

    private fun resolvePath(pathString: String): Path = executor.execute(
        {
            when {
                pathString.startsWith("classpath:") -> {
                    val resourcePath = pathString.substring("classpath:".length)
                    val resource = javaClass.classLoader.getResource(resourcePath)
                    if (resource != null) {
                        Paths.get(resource.toURI())
                    } else {
                        throw IllegalArgumentException("Resource not found: $resourcePath")
                    }
                }

                else -> Paths.get(pathString)
            }
        },
        TaskContext.of("BulkLoaderService", "resolvePath", pathString),
    )

    // Status tracking
    data class BulkLoadStatus(
        val isRunning: Boolean,
        val loadedCount: Int,
        val totalCharacters: Int,
        val errorCount: Int,
        val ratePerSecond: Double,
        val etaMinutes: Int,
    )

    fun getStatus(): BulkLoadStatus {
        val currentStartTime = startTime.get()
        return BulkLoadStatus(
            isRunning = isRunning.get(),
            loadedCount = loadedCount.get().toInt(),
            totalCharacters = totalCharacters.get().toInt(),
            errorCount = errorCount.get().toInt(),
            ratePerSecond = if (currentStartTime != null) {
                progressLogger.calculateRate(loadedCount.get().toInt(), currentStartTime)
            } else {
                0.0
            },
            etaMinutes = if (currentStartTime != null) {
                progressLogger.calculateEta(loadedCount.get().toInt(), totalCharacters.get().toInt(), currentStartTime)
            } else {
                0
            },
        )
    }

    fun stop() {
        stopRequested.set(true)
        isRunning.set(false)
    }
}
// DevTools warm restart test - 09:56:52

// Warm restart test 09:57:35

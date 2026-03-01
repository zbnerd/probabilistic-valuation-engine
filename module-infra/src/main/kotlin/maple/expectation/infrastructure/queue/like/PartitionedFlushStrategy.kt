package maple.expectation.infrastructure.queue.like

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.slf4j.LoggerFactory
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

/**
 * 파티션 기반 분산 Flush 전략 (#271 V5 Stateless Architecture)
 */
class PartitionedFlushStrategy @JvmOverloads constructor(
    private val redissonClient: RedissonClient,
    private val bufferStorage: RedisLikeBufferStorage,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val syncProcessor: BiConsumer<String, Long>,
    private val partitionCount: Int = 4,
    private val lockWaitMs: Long = 100L,
    private val lockLeaseMs: Long = 30000L,
    private val batchSize: Int = 1000
) {

    companion object {
        private val log = LoggerFactory.getLogger(PartitionedFlushStrategy::class.java)
    }

    init {
        log.info("[PartitionedFlushStrategy] Initialized with $partitionCount partitions")
    }

    /**
     * 담당 파티션 Flush 실행 (P0-10: Flush Race 해결)
     */
    fun flushAssignedPartitions(): FlushResult {
        return flushWithPartitions(syncProcessor)
    }

    /**
     * 파티션별 분산 Flush 실행
     */
    fun flushWithPartitions(processor: BiConsumer<String, Long>): FlushResult {
        // 1. 버퍼에서 데이터 원자적 추출
        val allEntries: Map<String, Long> = bufferStorage.fetchAndClear(batchSize)

        if (allEntries.isEmpty()) {
            log.debug("[Flush] No entries to process")
            return FlushResult.empty()
        }

        // 2. 파티션별 분류
        val partitioned: Map<Int, Map<String, Long>> = partitionEntries(allEntries)

        // 3. 각 파티션 처리 (분산 락)
        var acquiredPartitions = 0
        var processedEntries = 0
        var totalDelta = 0L
        var failedPartitions = 0

        for ((partitionId, entries) in partitioned) {
            val result = processPartition(partitionId, entries, processor)

            if (result.acquired) {
                acquiredPartitions++
                processedEntries += result.processedCount
                totalDelta += result.totalDelta

                if (!result.success) {
                    failedPartitions++
                }
            }
        }

        // 4. 메트릭 기록
        recordFlushMetrics(acquiredPartitions, processedEntries, totalDelta, failedPartitions)

        log.info(
            "[Flush] Completed: partitions=$acquiredPartitions/${partitioned.size}, entries=$processedEntries, delta=$totalDelta"
        )

        return FlushResult(acquiredPartitions, processedEntries, totalDelta, failedPartitions)
    }

    private fun processPartition(
        partitionId: Int,
        entries: Map<String, Long>,
        processor: BiConsumer<String, Long>
    ): PartitionResult {

        val lockKey = RedisKey.LIKE_FLUSH_PARTITION.withSuffix(partitionId.toString())
        val lock = redissonClient.getLock(lockKey)

        // tryLock: 이미 처리 중이면 스킵
        val acquired = executor.executeOrDefault(
            { lock.tryLock(lockWaitMs, lockLeaseMs, TimeUnit.MILLISECONDS) },
            false,
            TaskContext.of("Flush", "TryLock", partitionId.toString())
        )

        if (!acquired) {
            log.debug("[Flush] Partition $partitionId locked by another instance, skipping")
            // 획득 실패한 데이터는 버퍼로 복원
            restoreEntries(entries)
            return PartitionResult.notAcquired()
        }

        return executor.executeWithFinally(
            { doProcessPartition(partitionId, entries, processor) },
            { unlockSafely(lock) },
            TaskContext.of("Flush", "ProcessPartition", partitionId.toString())
        )
    }

    private fun doProcessPartition(
        partitionId: Int,
        entries: Map<String, Long>,
        processor: BiConsumer<String, Long>
    ): PartitionResult {

        var processedCount = 0
        var totalDelta = 0L
        val failedEntries = mutableListOf<Pair<String, Long>>()

        for ((userIgn, delta) in entries) {
            val success = executor.executeOrDefault(
                {
                    processor.accept(userIgn, delta)
                    true
                },
                false,
                TaskContext.of("Flush", "Process", userIgn)
            )

            if (success == true) {
                processedCount++
                totalDelta += delta
            } else {
                failedEntries.add(userIgn to delta)
            }
        }

        // 실패한 엔트리는 버퍼로 복원
        if (failedEntries.isNotEmpty()) {
            restoreEntries(failedEntries)
            log.warn(
                "[Flush] Partition $partitionId had ${failedEntries.size} failed entries, restored to buffer"
            )
        }

        return PartitionResult(true, failedEntries.isEmpty(), processedCount, totalDelta)
    }

    private fun partitionEntries(entries: Map<String, Long>): Map<Int, Map<String, Long>> {
        val partitioned = mutableMapOf<Int, MutableMap<String, Long>>()

        for ((userIgn, delta) in entries) {
            val partitionId = getPartitionId(userIgn)
            partitioned.getOrPut(partitionId) { mutableMapOf() }[userIgn] = delta
        }

        return partitioned
    }

    private fun getPartitionId(userIgn: String): Int {
        return kotlin.math.abs(userIgn.hashCode() % partitionCount)
    }

    private fun restoreEntries(entries: Map<String, Long>) {
        entries.forEach { (userIgn, delta) -> bufferStorage.increment(userIgn, delta) }
        meterRegistry.counter("like.flush.restore.entries").increment(entries.size.toDouble())
    }

    private fun restoreEntries(entries: List<Pair<String, Long>>) {
        entries.forEach { (userIgn, delta) -> bufferStorage.increment(userIgn, delta) }
        meterRegistry.counter("like.flush.restore.entries").increment(entries.size.toDouble())
    }

    private fun recordFlushMetrics(
        partitions: Int,
        entries: Int,
        delta: Long,
        failed: Int
    ) {
        meterRegistry.counter("like.flush.partitions.acquired").increment(partitions.toDouble())
        meterRegistry.counter("like.flush.entries.processed").increment(entries.toDouble())
        meterRegistry.counter("like.flush.delta.total").increment(delta.toDouble())
        if (failed > 0) {
            meterRegistry.counter("like.flush.partitions.failed").increment(failed.toDouble())
        }
    }

    private fun unlockSafely(lock: RLock) {
        if (lock.isHeldByCurrentThread) {
            lock.unlock()
        }
    }

    /**
     * Flush 결과
     */
    data class FlushResult(
        val acquiredPartitions: Int,
        val processedEntries: Int,
        val totalDelta: Long,
        val failedPartitions: Int
    ) {

        companion object {
            fun empty() = FlushResult(0, 0, 0, 0)
        }

        val hasProcessed: Boolean
            get() = processedEntries > 0

        val hasFailures: Boolean
            get() = failedPartitions > 0
    }

    /** 개별 파티션 처리 결과 */
    private data class PartitionResult(
        val acquired: Boolean,
        val success: Boolean,
        val processedCount: Int,
        val totalDelta: Long
    ) {

        companion object {
            fun notAcquired() = PartitionResult(false, false, 0, 0)
        }
    }
}

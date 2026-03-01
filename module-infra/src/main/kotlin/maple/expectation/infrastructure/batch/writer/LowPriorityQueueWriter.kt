package maple.expectation.infrastructure.batch.writer

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.core.port.out.QueueWriterPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.stereotype.Component

/**
 * Spring Batch ItemWriter for adding LOW priority tasks to PriorityCalculationQueue.
 *
 * **Functionality**
 * - Writes OCID strings as LOW priority tasks to the queue
 * - Records Micrometer metrics for queued and rejected tasks
 * - Handles backpressure when queue is full
 *
 * **CLAUDE.md Compliance**
 * - Section 12: LogicExecutor pattern for exception handling
 * - Section 15: Lambda limit - extracted private methods for 3+ line logic
 * - Stateless: No mutable instance state
 *
 * @see QueueWriterPort
 */
@Component
class LowPriorityQueueWriter(
    private val queue: QueueWriterPort,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : ItemWriter<String> {

    override fun write(ocids: Chunk<out String>) {
        val context = TaskContext.of("Batch", "LowPriorityQueueWriter", "write")

        executor.executeVoidJava({
            val queuedCount = AtomicInteger(0)
            val rejectedCount = AtomicInteger(0)

            for (ocid in ocids) {
                processSingleOcid(ocid, queuedCount, rejectedCount)
            }

            logWriteSummary(queuedCount.get(), rejectedCount.get())
        }, context)
    }

    /**
     * Process a single OCID by adding it to the LOW priority queue.
     * Section 15 compliance: Lambda extraction for 3+ line logic with metrics recording.
     */
    private fun processSingleOcid(
        ocid: String,
        queuedCount: AtomicInteger,
        rejectedCount: AtomicInteger
    ) {
        val accepted = queue.addLowPriorityTask(ocid)

        if (accepted) {
            recordQueued(ocid)
            queuedCount.incrementAndGet()
        } else {
            recordRejected(ocid)
            rejectedCount.incrementAndGet()
        }
    }

    /** Record successful queue addition metric. */
    private fun recordQueued(ocid: String) {
        Counter.builder(METRICS_QUEUED)
            .tag("priority", "low")
            .description("Number of tasks successfully queued for batch equipment refresh")
            .register(meterRegistry)
            .increment()
    }

    /** Record queue rejection metric (backpressure). */
    private fun recordRejected(ocid: String) {
        Counter.builder(METRICS_REJECTED)
            .tag("priority", "low")
            .tag("reason", "queue_full")
            .description("Number of tasks rejected due to queue backpressure")
            .register(meterRegistry)
            .increment()

        log.warn("[LowPriorityQueueWriter] Queue full, rejecting OCID: {}", ocid)
    }

    /** Log write operation summary. */
    private fun logWriteSummary(queuedCount: Int, rejectedCount: Int) {
        log.info(
            "[LowPriorityQueueWriter] Write complete - queued: {}, rejected: {}",
            queuedCount,
            rejectedCount
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LowPriorityQueueWriter::class.java)
        private const val METRICS_QUEUED = "batch.equipment.queued"
        private const val METRICS_REJECTED = "batch.equipment.rejected"
    }
}

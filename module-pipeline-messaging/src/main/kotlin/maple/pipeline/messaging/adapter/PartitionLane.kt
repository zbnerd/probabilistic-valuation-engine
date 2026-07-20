package maple.pipeline.messaging.adapter

import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.metrics.DeliveryMetrics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.Acknowledgment

class PartitionLane(
    private val subscription: PipelineSubscription,
    private val ownership: PartitionOwnership,
    private val adapter: KafkaDeliveryAdapter,
    private val partitionControl: PartitionControl,
    private val maxQueuedRecords: Int,
    private val metrics: DeliveryMetrics,
) {
    private data class Envelope(
        val record: ConsumerRecord<String, String>,
        val acknowledgment: Acknowledgment,
    )

    private val lock = ReentrantLock()
    private val queued = PriorityQueue<Envelope>(compareBy { envelope -> envelope.record.offset() })
    private var active: Envelope? = null
    private var revoked = false
    private var paused = false

    init {
        require(maxQueuedRecords > 0)
    }

    fun offer(record: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        val next = lock.withLock {
            if (revoked) {
                null
            } else {
                pauseIfNeeded()
                queued.add(Envelope(record, acknowledgment))
                recordHighWaterViolation()
                takeNextIfIdle()
            }
        }
        next?.let(::dispatch)
    }

    fun revoke() {
        lock.withLock {
            revoked = true
            queued.clear()
            active = null
        }
    }

    fun queuedRecordCount(): Int = lock.withLock { queued.size }

    private fun pauseIfNeeded() {
        if (!paused) {
            partitionControl.pausePartition(ownership.topicPartition)
            paused = true
        }
    }

    private fun recordHighWaterViolation() {
        if (queued.size > maxQueuedRecords) {
            metrics.recordInvariant(subscription.id, ownership.topicPartition.topic(), "QUEUE_HIGH_WATER")
        }
    }

    private fun takeNextIfIdle(): Envelope? {
        if (active != null) {
            return null
        }
        return queued.poll()?.also { active = it }
    }

    private fun dispatch(envelope: Envelope) {
        adapter.deliver(subscription, envelope.record, ownership)
            .whenComplete { action, failure -> completeDelivery(envelope, action, failure) }
    }

    private fun completeDelivery(envelope: Envelope, action: DeliveryAction?, failure: Throwable?) {
        if (failure != null) {
            metrics.recordInvariant(subscription.id, ownership.topicPartition.topic(), "ADAPTER_STAGE_FAILED")
            return
        }
        when (action) {
            DeliveryAction.Commit -> acknowledgeAndAdvance(envelope)
            DeliveryAction.OwnershipLost, null -> Unit
        }
    }

    private fun acknowledgeAndAdvance(envelope: Envelope) {
        if (!ownership.isCurrent() || lock.withLock { revoked || active !== envelope }) {
            return
        }
        val acknowledged = runCatching { envelope.acknowledgment.acknowledge() }.isSuccess
        if (!acknowledged) {
            metrics.recordInvariant(subscription.id, ownership.topicPartition.topic(), "ACK_FAILED")
            return
        }
        val next = lock.withLock {
            if (revoked || !ownership.isCurrent() || active !== envelope) {
                null
            } else {
                active = null
                takeNextIfIdle()
            }
        }
        if (next != null) {
            dispatch(next)
        } else {
            resumeIfDrained()
        }
    }

    private fun resumeIfDrained() {
        val shouldResume = lock.withLock {
            val drained = !revoked && ownership.isCurrent() && active == null && queued.isEmpty() && paused
            if (drained) {
                paused = false
            }
            drained
        }
        if (shouldResume) {
            partitionControl.resumePartition(ownership.topicPartition)
        }
    }
}

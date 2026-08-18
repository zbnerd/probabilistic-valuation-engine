package maple.pipeline.messaging.adapter

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.contract.PipelineSubscription
import maple.pipeline.messaging.dlt.DltPublisher
import maple.pipeline.messaging.dlt.DltRecordFactory
import maple.pipeline.messaging.metrics.DeliveryMetrics
import maple.pipeline.messaging.metrics.FailureClassifier
import maple.pipeline.messaging.policy.DeliveryRetryPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord

sealed interface DeliveryAction {
    data object Commit : DeliveryAction

    data object OwnershipLost : DeliveryAction
}

class KafkaDeliveryAdapter(
    private val retryPolicy: DeliveryRetryPolicy,
    private val dltPublisher: DltPublisher,
    private val dltRecordFactory: DltRecordFactory,
    private val deliveryExecutor: Executor,
    private val retryScheduler: ScheduledExecutorService,
    private val metrics: DeliveryMetrics,
) {
    fun deliver(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
    ): CompletionStage<DeliveryAction> {
        val result = CompletableFuture<DeliveryAction>()
        invokeHandler(subscription, record, ownership, attempt = 1, result = result)
        return result
    }

    private fun invokeHandler(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        attempt: Int,
        result: CompletableFuture<DeliveryAction>,
    ) {
        if (!ownership.isCurrent()) {
            result.complete(DeliveryAction.OwnershipLost)
            return
        }
        val context = deliveryContext(subscription, record, attempt)
        CompletableFuture.completedFuture(Unit)
            .thenComposeAsync({ subscription.handler.handle(record.value(), context) }, deliveryExecutor)
            .handle { outcome, failure -> resolveOutcome(outcome, failure) }
            .thenAccept { outcome ->
                handleOutcome(subscription, record, ownership, context, outcome, result)
            }
    }

    private fun resolveOutcome(outcome: DeliveryOutcome?, failure: Throwable?): DeliveryOutcome = failure
        ?.let { DeliveryOutcome.Retryable(CompletionFailures.unwrap(it)) }
        ?: requireNotNull(outcome)

    private fun handleOutcome(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        context: DeliveryContext,
        outcome: DeliveryOutcome,
        result: CompletableFuture<DeliveryAction>,
    ) {
        if (!ownership.isCurrent()) {
            result.complete(DeliveryAction.OwnershipLost)
            return
        }
        metrics.recordOutcome(subscription, record.topic(), outcome, context.deliveryAttempt)
        when (outcome) {
            DeliveryOutcome.Success -> result.complete(DeliveryAction.Commit)
            is DeliveryOutcome.TerminalDrop -> result.complete(DeliveryAction.Commit)
            is DeliveryOutcome.InvalidMessage -> startDlt(
                subscription,
                record,
                ownership,
                context,
                outcome.reason,
                result,
            )
            is DeliveryOutcome.Retryable -> handleRetryable(
                subscription,
                record,
                ownership,
                context,
                outcome,
                result,
            )
            is DeliveryOutcome.Backpressure -> handleBackpressure(
                subscription,
                record,
                ownership,
                context,
                outcome,
                result,
            )
        }
    }

    private fun handleRetryable(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        context: DeliveryContext,
        outcome: DeliveryOutcome.Retryable,
        result: CompletableFuture<DeliveryAction>,
    ) {
        val category = FailureClassifier.classify(outcome.cause)
        metrics.recordRetry(subscription, record.topic(), context.deliveryAttempt, category)
        if (context.deliveryAttempt <= retryPolicy.maxRetries) {
            schedule(retryPolicy.backoff, subscription, record.topic()) {
                invokeHandler(subscription, record, ownership, context.deliveryAttempt + 1, result)
            }
        } else {
            startDlt(subscription, record, ownership, context, RETRY_EXHAUSTED, result)
        }
    }

    private fun handleBackpressure(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        context: DeliveryContext,
        outcome: DeliveryOutcome.Backpressure,
        result: CompletableFuture<DeliveryAction>,
    ) {
        metrics.recordPause(subscription, record.topic(), outcome.duration)
        schedule(outcome.duration, subscription, record.topic()) {
            invokeHandler(subscription, record, ownership, context.deliveryAttempt, result)
        }
    }

    private fun startDlt(
        subscription: PipelineSubscription,
        source: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        context: DeliveryContext,
        reason: String,
        result: CompletableFuture<DeliveryAction>,
    ) {
        runCatching { dltRecordFactory.create(source, subscription.dltSanitizer, context) }
            .onSuccess { safeRecord -> publishDlt(subscription, safeRecord, ownership, reason, context.deliveryAttempt, result) }
            .onFailure { metrics.recordInvariant(subscription.id, source.topic(), "DLT_SANITIZE_FAILED") }
    }

    private fun publishDlt(
        subscription: PipelineSubscription,
        safeRecord: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        reason: String,
        attempt: Int,
        result: CompletableFuture<DeliveryAction>,
    ) {
        if (!ownership.isCurrent()) {
            result.complete(DeliveryAction.OwnershipLost)
            return
        }
        runCatching { dltPublisher.publish(safeRecord, reason, attempt) }
            .onSuccess { stage ->
                stage.whenComplete { _, failure ->
                    completeDltAttempt(subscription, safeRecord, ownership, reason, attempt, result, failure)
                }
            }
            .onFailure {
                scheduleDltOnlyRetry(subscription, safeRecord, ownership, reason, attempt, result)
            }
    }

    private fun completeDltAttempt(
        subscription: PipelineSubscription,
        safeRecord: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        reason: String,
        attempt: Int,
        result: CompletableFuture<DeliveryAction>,
        failure: Throwable?,
    ) {
        if (!ownership.isCurrent()) {
            result.complete(DeliveryAction.OwnershipLost)
        } else if (failure == null) {
            result.complete(DeliveryAction.Commit)
        } else {
            scheduleDltOnlyRetry(subscription, safeRecord, ownership, reason, attempt, result)
        }
    }

    private fun scheduleDltOnlyRetry(
        subscription: PipelineSubscription,
        safeRecord: ConsumerRecord<String, String>,
        ownership: PartitionOwnership,
        reason: String,
        attempt: Int,
        result: CompletableFuture<DeliveryAction>,
    ) {
        metrics.recordDltFailure(subscription, safeRecord.topic())
        schedule(retryPolicy.backoff, subscription, safeRecord.topic()) {
            publishDlt(subscription, safeRecord, ownership, reason, attempt, result)
        }
    }

    private fun schedule(
        duration: java.time.Duration,
        subscription: PipelineSubscription,
        topic: String,
        task: () -> Unit,
    ) {
        runCatching {
            retryScheduler.schedule(task, duration.toMillis(), TimeUnit.MILLISECONDS)
        }.onFailure {
            metrics.recordInvariant(subscription.id, topic, "RETRY_SCHEDULE_REJECTED")
        }
    }

    private fun deliveryContext(
        subscription: PipelineSubscription,
        record: ConsumerRecord<String, String>,
        attempt: Int,
    ): DeliveryContext = DeliveryContext(
        listenerId = subscription.id,
        topic = record.topic(),
        partition = record.partition(),
        offset = record.offset(),
        timestamp = record.timestamp()
            .takeUnless { it == ConsumerRecord.NO_TIMESTAMP }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.EPOCH,
        key = record.key(),
        deliveryAttempt = attempt,
    )

    companion object {
        const val RETRY_EXHAUSTED = "RETRY_EXHAUSTED"
    }
}

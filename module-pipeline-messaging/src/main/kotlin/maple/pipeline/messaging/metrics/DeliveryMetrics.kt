package maple.pipeline.messaging.metrics

import io.micrometer.core.instrument.MeterRegistry
import java.io.IOException
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.pipeline.messaging.contract.PipelineSubscription
import org.apache.kafka.common.KafkaException

enum class FailureCategory {
    TIMEOUT,
    IO,
    DB,
    KAFKA,
    OTHER,
}

object FailureClassifier {
    fun classify(failure: Throwable): FailureCategory = when (failure) {
        is TimeoutException -> FailureCategory.TIMEOUT
        is IOException -> FailureCategory.IO
        is SQLException -> FailureCategory.DB
        is KafkaException -> FailureCategory.KAFKA
        else -> FailureCategory.OTHER
    }
}

class DeliveryMetrics(
    private val registry: MeterRegistry,
) {
    private val healthy = AtomicBoolean(true)

    init {
        registry.gauge("pipeline.delivery.healthy", healthy) { state -> if (state.get()) 1.0 else 0.0 }
    }

    fun recordOutcome(
        subscription: PipelineSubscription,
        topic: String,
        outcome: DeliveryOutcome,
        attempt: Int,
    ) {
        val outcomeName = when (outcome) {
            DeliveryOutcome.Success -> "SUCCESS"
            is DeliveryOutcome.TerminalDrop -> "TERMINAL_DROP"
            is DeliveryOutcome.InvalidMessage -> "INVALID_MESSAGE"
            is DeliveryOutcome.Retryable -> "RETRYABLE"
            is DeliveryOutcome.Backpressure -> "BACKPRESSURE"
        }
        val reason = when (outcome) {
            DeliveryOutcome.Success -> "NONE"
            is DeliveryOutcome.TerminalDrop -> outcome.reason
            is DeliveryOutcome.InvalidMessage -> outcome.reason
            is DeliveryOutcome.Retryable -> FailureClassifier.classify(outcome.cause).name
            is DeliveryOutcome.Backpressure -> "CAPACITY"
        }
        registry.counter(
            "pipeline.delivery.outcomes",
            "subscription",
            subscription.id,
            "topic",
            topic,
            "outcome",
            outcomeName,
            "reason",
            reason,
            "attempt",
            attempt.coerceIn(1, 4).toString(),
        ).increment()
    }

    fun recordRetry(subscription: PipelineSubscription, topic: String, attempt: Int, category: FailureCategory) {
        registry.counter(
            "pipeline.delivery.retries",
            "subscription",
            subscription.id,
            "topic",
            topic,
            "attempt",
            attempt.coerceIn(1, 4).toString(),
            "category",
            category.name,
        ).increment()
    }

    fun recordPause(subscription: PipelineSubscription, topic: String, duration: Duration) {
        registry.timer(
            "pipeline.delivery.pause",
            "subscription",
            subscription.id,
            "topic",
            topic,
            "reason",
            "BACKPRESSURE",
        ).record(duration)
    }

    fun recordDltFailure(subscription: PipelineSubscription, topic: String) {
        healthy.set(false)
        registry.counter(
            "pipeline.delivery.dlt.failures",
            "subscription",
            subscription.id,
            "topic",
            topic,
            "reason",
            "SEND_FAILED",
        ).increment()
    }

    fun recordInvariant(subscriptionId: String, topic: String, reason: String) {
        healthy.set(false)
        registry.counter(
            "pipeline.delivery.invariant.failures",
            "subscription",
            subscriptionId,
            "topic",
            topic,
            "reason",
            reason,
        ).increment()
    }

    fun recordForcedShutdown(resource: String) {
        healthy.set(false)
        registry.counter(
            "pipeline.delivery.executor.forced.shutdown",
            "resource",
            resource,
        ).increment()
    }

    fun isHealthy(): Boolean = healthy.get()
}

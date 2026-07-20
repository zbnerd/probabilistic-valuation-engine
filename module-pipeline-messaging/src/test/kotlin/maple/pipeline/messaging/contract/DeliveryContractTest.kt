package maple.pipeline.messaging.contract

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import maple.pipeline.messaging.dlt.DltPayload
import maple.pipeline.messaging.dlt.DltRecordSanitizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeliveryContractTest {
    @Test
    fun `delivery context retains immutable Kafka metadata`() {
        val timestamp = Instant.parse("2026-07-19T12:34:56Z")

        val context = DeliveryContext(
            listenerId = "calculator-normal",
            topic = "external-api.snapshot.chunk-ready",
            partition = 3,
            offset = 42L,
            timestamp = timestamp,
            key = "run-1:chunk-2",
            deliveryAttempt = 2,
        )

        assertThat(context).isEqualTo(
            DeliveryContext(
                listenerId = "calculator-normal",
                topic = "external-api.snapshot.chunk-ready",
                partition = 3,
                offset = 42L,
                timestamp = timestamp,
                key = "run-1:chunk-2",
                deliveryAttempt = 2,
            ),
        )
    }

    @Test
    fun `backpressure duration must be positive`() {
        assertThatThrownBy { DeliveryOutcome.Backpressure(Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("backpressure duration must be positive")
        assertThatThrownBy { DeliveryOutcome.Backpressure(Duration.ofMillis(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(DeliveryOutcome.Backpressure(Duration.ofMillis(1)).duration)
            .isEqualTo(Duration.ofMillis(1))
    }

    @Test
    fun `terminal reasons use a bounded normalized vocabulary`() {
        assertThatThrownBy { DeliveryOutcome.TerminalDrop("lower-case") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DeliveryOutcome.InvalidMessage("A".repeat(65)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(DeliveryOutcome.InvalidMessage("UNSUPPORTED_SCHEMA").reason)
            .isEqualTo("UNSUPPORTED_SCHEMA")
    }

    @Test
    fun `subscription copies topics and handler returns a completion stage`() {
        val sourceTopics = mutableListOf("topic-a")
        val handler = DeliveryHandler { _, _ -> CompletableFuture.completedFuture(DeliveryOutcome.Success) }
        val subscription = PipelineSubscription(
            id = "listener-a",
            topics = sourceTopics,
            groupId = "group-a",
            concurrency = 2,
            handler = handler,
            dltSanitizer = DltRecordSanitizer.PassThrough,
        )

        sourceTopics += "topic-b"
        assertThat(subscription.topics).containsExactly("topic-a")
        assertThatThrownBy { (subscription.topics as MutableList<String>).add("topic-c") }
            .isInstanceOf(UnsupportedOperationException::class.java)

        val result: CompletionStage<DeliveryOutcome> = subscription.handler.handle(
            "{}",
            context(),
        )
        assertThat(result.toCompletableFuture()).isCompletedWithValue(DeliveryOutcome.Success)
    }

    @Test
    fun `DLT payload defensively copies header arrays and map`() {
        val headerBytes = byteArrayOf(1, 2, 3)
        val sourceHeaders = linkedMapOf("x-pipeline-safe-digest" to headerBytes)

        val payload = DltPayload("safe-key", "safe-value", sourceHeaders)
        headerBytes[0] = 9
        sourceHeaders["x-pipeline-safe-other"] = byteArrayOf(4)

        assertThat(payload.extraHeaders.keys).containsExactly("x-pipeline-safe-digest")
        assertThat(payload.extraHeaders.getValue("x-pipeline-safe-digest"))
            .containsExactly(1, 2, 3)
    }

    @Test
    fun `DLT payload rejects unsafe or oversized headers`() {
        assertThatThrownBy {
            DltPayload(null, "safe", mapOf("authorization" to byteArrayOf(1)))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            DltPayload(null, "safe", mapOf("x-pipeline-safe-large" to ByteArray(1025)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pass through sanitizer keeps only key and value`() {
        val payload = DltRecordSanitizer.PassThrough.sanitize("key", "value", context())

        assertThat(payload.key).isEqualTo("key")
        assertThat(payload.value).isEqualTo("value")
        assertThat(payload.extraHeaders).isEmpty()
    }

    @Test
    fun `completion failures unwrap only nested completion wrappers`() {
        val cause = IllegalStateException("domain failure")
        val wrapped = CompletionException(ExecutionException(CompletionException(cause)))

        assertThat(CompletionFailures.unwrap(wrapped)).isSameAs(cause)
    }

    @Test
    fun `completion failures preserve non-wrapper cause object`() {
        val cause = IllegalArgumentException("unchanged")

        assertThat(CompletionFailures.unwrap(cause)).isSameAs(cause)
    }

    @Test
    fun `safe delivery exception carries no source failure material`() {
        val exception = SafeDeliveryException("RETRY_EXHAUSTED", 4)

        assertThat(exception.message).isEqualTo("pipeline delivery RETRY_EXHAUSTED attempt=4")
        assertThat(exception.cause).isNull()
        assertThat(exception.suppressed).isEmpty()
        assertThat(exception.stackTrace).isEmpty()
    }

    private fun context(): DeliveryContext = DeliveryContext(
        listenerId = "listener-a",
        topic = "topic-a",
        partition = 0,
        offset = 7L,
        timestamp = Instant.EPOCH,
        key = "key",
        deliveryAttempt = 1,
    )
}

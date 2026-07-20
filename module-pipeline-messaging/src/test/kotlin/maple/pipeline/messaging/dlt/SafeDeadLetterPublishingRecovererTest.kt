package maple.pipeline.messaging.dlt

import maple.pipeline.messaging.contract.SafeDeliveryException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders

class SafeDeadLetterPublishingRecovererTest {
    @Test
    fun `producer record retains only bounded original exception and pipeline-safe headers`() {
        val recoverer = ExposedRecoverer(mock())
        val headers = RecordHeaders()
            .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "source".toByteArray())
            .add(KafkaHeaders.DLT_ORIGINAL_PARTITION, byteArrayOf(0, 0, 0, 2))
            .add(KafkaHeaders.DLT_ORIGINAL_OFFSET, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9))
            .add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
            .add(KafkaHeaders.DLT_EXCEPTION_FQCN, SafeDeliveryException::class.java.name.toByteArray())
            .add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "SECRET-SENTINEL".toByteArray())
            .add(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "SECRET-STACK".toByteArray())
            .add("authorization", "SECRET-AUTH".toByteArray())
            .add("x-pipeline-safe-reason", "BAD_JSON".toByteArray())
            .add("x-pipeline-safe-attempt", "1".toByteArray())

        val producer = recoverer.expose(
            ConsumerRecord("source", 2, 9L, null, "sanitized"),
            TopicPartition("source.DLT", 2),
            headers,
        )
        val rendered = producer.headers().joinToString("|") { header ->
            "${header.key()}=${String(header.value())}"
        }

        assertThat(producer.topic()).isEqualTo("source.DLT")
        assertThat(producer.partition()).isEqualTo(2)
        assertThat(rendered)
            .contains(KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.DLT_EXCEPTION_FQCN, "x-pipeline-safe-reason")
            .doesNotContain("SECRET-SENTINEL", "SECRET-STACK", "SECRET-AUTH", "authorization")
    }
}

private class ExposedRecoverer(
    template: KafkaTemplate<String, String>,
) : SafeDeadLetterPublishingRecoverer(template) {
    fun expose(
        record: ConsumerRecord<*, *>,
        topicPartition: TopicPartition,
        headers: Headers,
    ): ProducerRecord<Any, Any> = createProducerRecord(record, topicPartition, headers, null, null)
}

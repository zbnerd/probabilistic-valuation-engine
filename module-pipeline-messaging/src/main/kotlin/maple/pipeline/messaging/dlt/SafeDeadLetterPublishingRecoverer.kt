package maple.pipeline.messaging.dlt

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.BiFunction
import maple.pipeline.messaging.contract.SafeDeliveryException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.support.KafkaHeaders

open class SafeDeadLetterPublishingRecoverer(
    kafkaTemplate: KafkaTemplate<String, String>,
) : DeadLetterPublishingRecoverer(
    kafkaTemplate,
    BiFunction { record, _ -> TopicPartition("${record.topic()}.DLT", record.partition()) },
) {
    init {
        setFailIfSendResultIsError(true)
        setWaitForSendResultTimeout(Duration.ofSeconds(10))
        setVerifyPartition(true)
        setPartitionInfoTimeout(Duration.ofSeconds(10))
        setAppendOriginalHeaders(false)
        setStripPreviousExceptionHeaders(true)
        excludeHeader(
            HeaderNames.HeadersToAdd.EX_CAUSE,
            HeaderNames.HeadersToAdd.EX_MSG,
            HeaderNames.HeadersToAdd.EX_STACKTRACE,
            HeaderNames.HeadersToAdd.GROUP,
            HeaderNames.HeadersToAdd.TS_TYPE,
        )
        setHeadersFunction { _, failure -> safePipelineHeaders(failure) }
    }

    override fun createProducerRecord(
        record: ConsumerRecord<*, *>,
        topicPartition: TopicPartition,
        headers: Headers,
        keyBytes: ByteArray?,
        valueBytes: ByteArray?,
    ): ProducerRecord<Any, Any> {
        val normalized = super.createProducerRecord(record, topicPartition, headers, keyBytes, valueBytes)
        val safeHeaders = RecordHeaders()
        normalized.headers().forEach { header ->
            if (header.key() in ALLOWED_SPRING_HEADERS || header.key().startsWith(DltPayload.SAFE_HEADER_PREFIX)) {
                safeHeaders.add(header.key(), header.value().copyOf())
            }
        }
        return ProducerRecord(
            normalized.topic(),
            normalized.partition(),
            normalized.timestamp(),
            normalized.key(),
            normalized.value(),
            safeHeaders,
        )
    }

    private fun safePipelineHeaders(failure: Exception): Headers {
        val safeFailure = failure as? SafeDeliveryException
        val headers = RecordHeaders()
        safeFailure?.let {
            headers.add(SAFE_REASON_HEADER, it.reason.toByteArray(StandardCharsets.UTF_8))
            headers.add(SAFE_ATTEMPT_HEADER, it.attempt.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return headers
    }

    companion object {
        const val SAFE_REASON_HEADER = "x-pipeline-safe-reason"
        const val SAFE_ATTEMPT_HEADER = "x-pipeline-safe-attempt"

        private val ALLOWED_SPRING_HEADERS = setOf(
            KafkaHeaders.DLT_ORIGINAL_TOPIC,
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            KafkaHeaders.DLT_ORIGINAL_TIMESTAMP,
            KafkaHeaders.DLT_EXCEPTION_FQCN,
        )
    }
}

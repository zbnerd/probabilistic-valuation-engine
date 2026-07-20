package maple.pipeline.messaging.dlt

import java.nio.charset.StandardCharsets
import maple.pipeline.messaging.contract.DeliveryContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders

class DltRecordFactory {
    fun create(
        source: ConsumerRecord<String, String>,
        sanitizer: DltRecordSanitizer,
        context: DeliveryContext,
    ): ConsumerRecord<String, String> {
        val sanitized = sanitizer.sanitize(source.key(), source.value(), context)
        val headers = RecordHeaders()
        sanitized.extraHeaders.forEach { (name, value) -> headers.add(name, value.copyOf()) }
        return ConsumerRecord(
            source.topic(),
            source.partition(),
            source.offset(),
            source.timestamp(),
            source.timestampType(),
            null,
            serializedSize(sanitized.key),
            serializedSize(sanitized.value),
            sanitized.key,
            sanitized.value,
            headers,
            source.leaderEpoch(),
        )
    }

    private fun serializedSize(value: String?): Int = value
        ?.toByteArray(StandardCharsets.UTF_8)
        ?.size
        ?: ConsumerRecord.NULL_SIZE
}

package maple.pipeline.messaging.dlt

import java.time.Instant
import maple.pipeline.messaging.contract.DeliveryContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DltRecordFactoryTest {
    @Test
    fun `creates source-metadata record from sanitized material without arbitrary headers`() {
        val source = ConsumerRecord(
            "auth-character-fetch-request",
            2,
            17L,
            1_721_390_400_000L,
            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
            null,
            4,
            20,
            "raw-secret-key",
            "raw-api-key-payload",
            RecordHeaders(listOf(RecordHeader("authorization", "secret".toByteArray()))),
            java.util.Optional.of(9),
        )
        val sanitizer = DltRecordSanitizer { _, _, _ ->
            DltPayload(
                key = null,
                value = "{\"sha256\":\"abc\",\"length\":19}",
                extraHeaders = mapOf("x-pipeline-safe-event-id" to "event-1".toByteArray()),
            )
        }

        val safe = DltRecordFactory().create(source, sanitizer, context())

        assertThat(safe.topic()).isEqualTo(source.topic())
        assertThat(safe.partition()).isEqualTo(source.partition())
        assertThat(safe.offset()).isEqualTo(source.offset())
        assertThat(safe.timestamp()).isEqualTo(source.timestamp())
        assertThat(safe.leaderEpoch()).isEqualTo(source.leaderEpoch())
        assertThat(safe.key()).isNull()
        assertThat(safe.value()).doesNotContain("raw-api-key-payload", "raw-secret-key")
        assertThat(safe.headers().toArray().map { header -> header.key() })
            .containsExactly("x-pipeline-safe-event-id")
    }

    private fun context(): DeliveryContext = DeliveryContext(
        listenerId = "external-auth",
        topic = "auth-character-fetch-request",
        partition = 2,
        offset = 17L,
        timestamp = Instant.ofEpochMilli(1_721_390_400_000L),
        key = "raw-secret-key",
        deliveryAttempt = 1,
    )
}

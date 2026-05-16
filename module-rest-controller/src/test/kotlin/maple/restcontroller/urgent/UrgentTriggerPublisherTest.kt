package maple.restcontroller.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class UrgentTriggerPublisherTest {

    private val kafkaTemplate: KafkaTemplate<String, String> = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val topic = "urgent-character-request"
    private val publisher = UrgentTriggerPublisher(kafkaTemplate, objectMapper, topic)

    @Test
    fun `publish sends message with userIgn as key`() {
        val request = UrgentCharacterRequest(userIgn = "testCharacter")
        val producerRecord = ProducerRecord(topic, "testCharacter", "{}")
        val recordMetadata = RecordMetadata(TopicPartition(topic, 0), 0L, 0L, 0L, 0L.toLong(), 0, 0)
        val sendResult = CompletableFuture.completedFuture(
            SendResult<String, String>(producerRecord, recordMetadata)
        )

        whenever(kafkaTemplate.send(eq(topic), eq("testCharacter"), any())).thenReturn(sendResult)

        publisher.publish(request)

        verify(kafkaTemplate).send(eq(topic), eq("testCharacter"), argThat { contains("testCharacter") })
    }
}

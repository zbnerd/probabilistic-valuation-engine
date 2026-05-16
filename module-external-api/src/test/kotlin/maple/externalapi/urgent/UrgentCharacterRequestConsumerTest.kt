package maple.externalapi.urgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.SendResult
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class UrgentCharacterRequestConsumerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var objectMapper: ObjectMapper
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var consumer: UrgentCharacterRequestConsumer

    @BeforeEach
    fun setUp() {
        clientPort = mock()
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        kafkaTemplate = mock()

        consumer = UrgentCharacterRequestConsumer(
            clientPort = clientPort,
            objectMapper = objectMapper,
            kafkaTemplate = kafkaTemplate,
            notFoundTopic = "urgent-character-not-found",
            urgentChunkReadyTopic = "external-api.urgent.snapshot.chunk-ready",
            storeBasePath = tempDir.toString(),
        )
    }

    private fun stubSendResult(): CompletableFuture<SendResult<String, String>> {
        val future = CompletableFuture<SendResult<String, String>>()
        val record = ProducerRecord<String, String>("topic", "key", "value")
        val metadata = RecordMetadata(TopicPartition("topic", 0), 0L, 0, 0L, 0, 0)
        future.complete(SendResult(record, metadata))
        return future
    }

    @Test
    fun `successful path - resolves OCID, fetches basic and equipment, publishes 2 chunks`() {
        // Given
        val userIgn = "TestCharacter"
        val ocid = "abc123ocid"
        val request = UrgentCharacterRequest(
            eventId = UUID.randomUUID().toString(),
            userIgn = userIgn,
            presetNo = 1,
            requestedAt = Instant.now(),
        )
        val message = objectMapper.writeValueAsString(request)

        val ocidResponse = """{"ocid":"$ocid"}""".toByteArray()
        val basicResponse = """{"character_name":"TestCharacter","level":300}""".toByteArray()
        val equipmentResponse = """{"item_equipment":[{"item_name":"sword"}]}""".toByteArray()

        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, userIgn))
            .thenReturn(CompletableFuture.completedFuture(ocidResponse))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.CHARACTER_BASIC, ocid))
            .thenReturn(CompletableFuture.completedFuture(basicResponse))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.ITEM_EQUIPMENT, ocid))
            .thenReturn(CompletableFuture.completedFuture(equipmentResponse))
        whenever(kafkaTemplate.send(any(), any(), any()))
            .thenReturn(stubSendResult())

        val acknowledgment = mock<Acknowledgment>()

        // When
        consumer.consume(message, acknowledgment)

        // Then - verify 2 chunk-ready events published
        val captor = argumentCaptor<String>()
        verify(kafkaTemplate, times(2)).send(
            eq("external-api.urgent.snapshot.chunk-ready"),
            any(),
            captor.capture(),
        )

        val chunkEvents = captor.allValues.map { objectMapper.readTree(it) }
        assertThat(chunkEvents.all { it["eventType"].asText() == "SNAPSHOT_CHUNK_READY" }).isTrue()

        val endpoints = chunkEvents.map { it["endpoint"].asText() }.toSet()
        assertThat(endpoints).containsExactlyInAnyOrder("character-basic", "item-equipment")

        // Verify chunk files exist on disk (consumer creates tempDir/runs/urgent-xxx/...)
        val runsDir = tempDir.resolve("runs").toFile()
        assertThat(runsDir.exists()).isTrue()
        val urgentDirs = runsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("urgent-") }
        assertThat(urgentDirs).isNotEmpty

        val runDir = urgentDirs!!.first()
        val chunksFiles = runDir.walkTopDown().filter { it.extension == "gz" }.toList()
        assertThat(chunksFiles).hasSize(2)
    }

    @Test
    fun `not-found path - OCID response missing ocid field publishes not-found event`() {
        // Given
        val userIgn = "NonExistent"
        val request = UrgentCharacterRequest(
            eventId = UUID.randomUUID().toString(),
            userIgn = userIgn,
            presetNo = 1,
            requestedAt = Instant.now(),
        )
        val message = objectMapper.writeValueAsString(request)

        val ocidResponse = """{"error":{"name":"OPENAPI00004","message":"Data not found"}}""".toByteArray()

        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, userIgn))
            .thenReturn(CompletableFuture.completedFuture(ocidResponse))
        whenever(kafkaTemplate.send(any(), any(), any()))
            .thenReturn(stubSendResult())

        val acknowledgment = mock<Acknowledgment>()

        // When
        consumer.consume(message, acknowledgment)

        // Then
        val captor = argumentCaptor<String>()
        verify(kafkaTemplate).send(
            eq("urgent-character-not-found"),
            any(),
            captor.capture(),
        )

        val notFoundEvent = objectMapper.readTree(captor.firstValue)
        assertThat(notFoundEvent["userIgn"].asText()).isEqualTo(userIgn)
        assertThat(notFoundEvent["reason"].asText()).isEqualTo("OCID_NOT_FOUND")

        // No chunk files created
        val runsDir = tempDir.resolve("runs").toFile()
        assertThat(runsDir.exists()).isFalse()
    }
}

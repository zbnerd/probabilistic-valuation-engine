package maple.synchronizer.consumer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.event.ResultChunkEventPathBuilder
import maple.synchronizer.metrics.SynchronizerChunkMetricsListener
import maple.synchronizer.processor.ChunkProcessor
import maple.synchronizer.service.BasicChunkIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ChunkConsumerMappingTest {

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `basic consumer delegates parsed event to ingestion service`() {
        val ingestionService = mock<BasicChunkIngestionService>()
        whenever(ingestionService.process(any(), any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))
        val consumer = BasicSnapshotChunkConsumer(
            objectMapper = objectMapper,
            ingestionService = ingestionService,
        )

        consumer.consume(basicMessage, context("basic-topic", null))

        val eventCaptor = argumentCaptor<SnapshotChunkReadyEvent>()
        verify(ingestionService).process(
            eventCaptor.capture(),
            eq(basicMessage),
            eq("basic-topic"),
            eq(null),
            eq(false),
        )
        val event = eventCaptor.firstValue
        assertThat(event.runId).isEqualTo("run-basic")
        assertThat(event.endpoint).isEqualTo("character-basic")
        assertThat(event.chunkId).isEqualTo("chunk-1")
    }

    @Test
    fun `result consumer maps event metadata to result chunk execution request`() {
        val template = mock<ChunkConsumerTemplate>()
        whenever(template.submit(any())).thenReturn(CompletableFuture.completedFuture(DeliveryOutcome.Success))
        val consumer = KafkaResultChunkConsumer(
            objectMapper = objectMapper,
            chunkProcessor = mock<ChunkProcessor>(),
            chunkMetricsListener = mock<SynchronizerChunkMetricsListener>(),
            chunkConsumerTemplate = template,
            consumedEventPublisher = mock<KafkaChunkConsumedEventPublisher>(),
            eventPathBuilder = mock<ResultChunkEventPathBuilder>(),
            executor = mock(),
        )

        consumer.consume(resultMessage, context("result-topic", "result-key"))

        val captor = argumentCaptor<ChunkConsumerRequest>()
        verify(template).submit(captor.capture())
        val request = captor.firstValue
        assertThat(request.identity.executionType).isEqualTo(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)
        assertThat(request.identity.runId).isEqualTo("run-result")
        assertThat(request.identity.endpoint).isEqualTo("equipment")
        assertThat(request.identity.chunkId).isEqualTo("chunk-2")
        assertThat(request.topic).isEqualTo("result-topic")
        assertThat(request.messageKey).isEqualTo("result-key")
        assertThat(request.eventType).isEqualTo("CALCULATOR_RESULT_CHUNK_READY")
        assertThat(request.schemaVersion).isEqualTo(1)
        assertThat(request.eventPayloadJson).isEqualTo(resultMessage)
    }

    private fun context(topic: String, key: String?): DeliveryContext = DeliveryContext(
        listenerId = "test-listener",
        topic = topic,
        partition = 0,
        offset = 1,
        timestamp = Instant.EPOCH,
        key = key,
        deliveryAttempt = 1,
    )

    private companion object {
        private val basicMessage = """
            {
              "eventId": "event-basic",
              "eventType": "SNAPSHOT_CHUNK_READY",
              "schemaVersion": 1,
              "runId": "run-basic",
              "endpoint": "character-basic",
              "chunkId": "chunk-1",
              "objectKey": "basic/chunk-1.jsonl.gz",
              "recordCount": 10,
              "uncompressedBytes": 100,
              "compressedBytes": 50,
              "createdAt": "2026-05-18T00:00:00Z"
            }
        """.trimIndent()

        private val resultMessage = """
            {
              "eventId": "event-result",
              "eventType": "CALCULATOR_RESULT_CHUNK_READY",
              "schemaVersion": 1,
              "sourceRunId": "run-result",
              "sourceEndpoint": "equipment",
              "sourceChunkId": "chunk-2",
              "objectKey": "result/chunk-2.jsonl.gz",
              "sourceRecordCount": 10,
              "resultCount": 9,
              "errorCount": 1,
              "uncompressedBytes": 200,
              "compressedBytes": 80,
              "createdAt": "2026-05-18T00:00:00Z"
            }
        """.trimIndent()
    }
}

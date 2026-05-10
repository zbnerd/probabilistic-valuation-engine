package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.synchronizer.builder.EquipmentDocumentBuilder
import maple.synchronizer.event.CalculatorResultChunkReadyEvent
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.repository.EquipmentReadModelRepository
import maple.synchronizer.repository.SynchronizerChunkStatusRepository
import maple.synchronizer.storage.ResultFileReader
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Component
class KafkaResultChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val resultFileReader: ResultFileReader,
    private val documentBuilder: EquipmentDocumentBuilder,
    private val readModelRepository: EquipmentReadModelRepository,
    private val chunkStatusRepository: SynchronizerChunkStatusRepository,
    private val metrics: SynchronizerMetrics,
) {
    private val log = LoggerFactory.getLogger(KafkaResultChunkConsumer::class.java)
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @KafkaListener(
        topics = ["\${synchronizer.kafka.result-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId

        val chunkSample = Timer.start()
        metrics.incrementReceived()
        metrics.recordStatusTransition("RECEIVED")

        log.info(
            "[Synchronizer] received: runId={} chunkId={} objectKey={} results={}",
            runId, chunkId, event.objectKey, event.resultCount,
        )

        try {
            chunkStatusRepository.markReceived(runId, chunkId, event.objectKey)
            chunkStatusRepository.markProcessing(runId, chunkId)
            metrics.recordStatusTransition("PROCESSING")

            val grouped = timed(metrics.fileReadTimer()) { resultFileReader.readAndGroupByCompositeKey(event.objectKey) }
            val documents = timed(metrics.documentBuildTimer()) { grouped.map { documentBuilder.build(runId, chunkId, it) } }
            val itemsCount = grouped.sumOf { it.items.size.toLong() }

            log.info("[Synchronizer] grouped {} results into {} documents", event.resultCount, documents.size)

            metrics.incrementDocuments(documents.size)
            metrics.incrementItems(itemsCount)
            metrics.recordChunkSize(documents.size, itemsCount, documents.size.toLong())
            documents.forEach { metrics.recordDocumentEquipment(it.summary.equipmentCount) }

            CompletableFuture.runAsync({
                metrics.mainUpsertTimer().record(Runnable { readModelRepository.bulkUpsert(runId, chunkId, documents) })
            }, vtExecutor)
                .thenRun {
                    chunkStatusRepository.markSuccess(runId, chunkId)
                    metrics.incrementProcessed()
                    metrics.recordStatusTransition("SUCCESS")
                    chunkSample.stop(metrics.chunkTimer())
                }
                .join()

            acknowledgment.acknowledge()
        } catch (e: Exception) {
            metrics.incrementFailed()
            metrics.recordStatusTransition("FAILED")
            chunkStatusRepository.markFailed(runId, chunkId, e.message ?: "unknown")
            log.error("[Synchronizer] chunk processing failed: runId={} chunkId={}", runId, chunkId, e)
            throw e
        }
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}

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
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

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

    // Permit covers entire chunk processing: file read → parse → build → upsert
    private val processingPermit = Semaphore(2)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.result-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, CalculatorResultChunkReadyEvent::class.java)
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId

        log.info("[Synchronizer] received: runId={} chunkId={} objectKey={} results={}",
            runId, chunkId, event.objectKey, event.resultCount)

        // 1. Idempotency: skip already-successful chunks
        if (chunkStatusRepository.isAlreadySuccess(runId, chunkId)) {
            log.info("[Synchronizer] skip already-successful chunk: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        // 2. Atomic claim: only this worker proceeds if claim succeeds
        if (!chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey)) {
            log.info("[Synchronizer] skip - chunk already claimed by another worker: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        // 3. Try acquire processing permit — covers entire chunk lifecycle
        if (!processingPermit.tryAcquire()) {
            log.info("[Synchronizer] processing permit busy, will retry: runId={} chunkId={}", runId, chunkId)
            // DON'T ACK — message will be redelivered after poll timeout
            return
        }

        // 4. Dispatch full processing to virtual thread — only event metadata is passed
        metrics.incrementReceived()
        metrics.incrementProcessing()

        CompletableFuture.runAsync({
            processChunk(event, acknowledgment)
        }, vtExecutor)
    }

    private fun processChunk(event: CalculatorResultChunkReadyEvent, acknowledgment: Acknowledgment) {
        val runId = event.sourceRunId
        val chunkId = event.sourceChunkId
        val chunkSample = Timer.start()

        MDC.put("runId", runId)
        MDC.put("chunkId", chunkId)
        MDC.put("kafkaTopic", "calculator.result.chunk-ready")
        try {
            // All heavy work happens here — inside the processing permit
            val grouped = timed(metrics.fileReadTimer()) {
                resultFileReader.readAndGroupByCompositeKey(event.objectKey)
            }
            val documents = timed(metrics.documentBuildTimer()) {
                grouped.map { documentBuilder.build(runId, chunkId, it) }
            }
            val itemsCount = grouped.sumOf { it.items.size.toLong() }

            log.info("[Synchronizer] grouped {} results into {} documents", event.resultCount, documents.size)

            metrics.incrementDocuments(documents.size)
            metrics.incrementItems(itemsCount)
            metrics.recordChunkSize(documents.size, itemsCount, event.compressedBytes)
            documents.forEach { metrics.recordDocumentEquipment(it.summary.equipmentCount) }

            metrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount.toLong())
            val ratio = if (event.compressedBytes > 0) "%.2f".format(event.uncompressedBytes.toDouble() / event.compressedBytes.toDouble()) else "N/A"
            log.info(
                "[preUpsertVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} documents={} compressionRatio={}",
                runId, chunkId, event.compressedBytes, event.uncompressedBytes, event.resultCount, documents.size, ratio,
            )

            // DB upsert — still inside same permit
            metrics.mainUpsertTimer().record(Runnable {
                readModelRepository.bulkUpsert(runId, chunkId, documents)
            })

            chunkStatusRepository.markSuccess(runId, chunkId)
            metrics.incrementProcessed()
            metrics.recordStatusTransition("SUCCESS")
            chunkSample.stop(metrics.chunkTimer())
            acknowledgment.acknowledge()
        } catch (e: Exception) {
            chunkStatusRepository.markFailed(runId, chunkId, e.message ?: "unknown")
            metrics.incrementFailed()
            metrics.recordStatusTransition("FAILED")
            log.error("[Synchronizer] chunk processing failed: runId={} chunkId={}", runId, chunkId, e)
            // DON'T ACK — message will be redelivered
        } finally {
            metrics.decrementProcessing()
            processingPermit.release()
            MDC.clear()
        }
    }

    private fun unwrapCompletionException(ex: Throwable): Throwable {
        val cause = ex.cause
        return if (cause != null && ex is java.util.concurrent.CompletionException) cause else ex
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}

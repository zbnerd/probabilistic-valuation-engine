package maple.calculator.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.processor.SnapshotChunkProcessor
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val objectMapper: ObjectMapper,
    private val chunkProcessor: SnapshotChunkProcessor,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val concurrency = Semaphore(2)

    @KafkaListener(
        topics = ["\${calculator.kafka.snapshot-chunk-ready-topic}"],
        groupId = "\${calculator.kafka.consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        log.info(
            "[Consumer] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            event.runId, event.endpoint, event.chunkId, event.objectKey, event.recordCount,
        )

        if (event.endpoint != "item-equipment") {
            log.info("[Consumer] skipping non-item-equipment endpoint: {}", event.endpoint)
            acknowledgment.acknowledge()
            return
        }

        scope.launch {
            concurrency.acquire()
            try {
                val result = chunkProcessor.process(event.objectKey)
                log.info(
                    "[Consumer] processed chunk: runId={} chunkId={} records={} success={} items={}",
                    event.runId, event.chunkId, result.recordCount, result.successCount, result.totalItems,
                )
                acknowledgment.acknowledge()
            } catch (e: Exception) {
                log.error("[Consumer] chunk processing failed: runId={} chunkId={}: {}", event.runId, event.chunkId, e.message)
            } finally {
                concurrency.release()
            }
        }
    }
}

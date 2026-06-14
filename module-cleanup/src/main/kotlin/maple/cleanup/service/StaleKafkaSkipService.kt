package maple.cleanup.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.Properties
import maple.cleanup.config.CleanupProperties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Scans a Kafka topic for chunk-ready/result messages whose payload runId
 * does not match the supplied `keepRunIds`. **Scan only** — does NOT commit
 * any offsets, so it is safe to run while the live consumer in
 * `consumerGroup` is actively polling.
 *
 * For actually skipping stale records, use the Kafka admin CLI while the
 * modules are down:
 *
 *   kafka-consumer-groups --bootstrap-server $KAFKA \
 *     --group <consumer-group> \
 *     --topic <topic> \
 *     --reset-offsets --to-offset <N> --execute
 *
 * Where <N> is the highest offset whose payload runId is NOT in keepRunIds
 * plus 1 (returned by this scan as `lastStaleOffset + 1`).
 */
@Service
class StaleKafkaSkipService(
    private val properties: CleanupProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(StaleKafkaSkipService::class.java)

    data class StaleRecord(
        val partition: Int,
        val offset: Long,
        val runId: String?,
    )

    data class PartitionScanResult(
        val partition: Int,
        val earliestOffset: Long,
        val latestOffset: Long,
        val recordsScanned: Int,
        val staleRecords: List<StaleRecord>,
    )

    data class ScanResult(
        val topic: String,
        val consumerGroup: String,
        val keepRunIds: Set<String>,
        val partitionsScanned: Int,
        val totalStaleMessages: Long,
        val recommendedResetOffset: Map<Int, Long>,
        val perPartition: List<PartitionScanResult>,
    )

    fun scanForStaleMessages(
        topic: String,
        consumerGroup: String,
        keepRunIds: Set<String>,
        scanLimitPerPartition: Int = 1000,
        bootstrap: String = properties.kafkaBootstrapServers,
    ): ScanResult {
        val consumer = newConsumer(bootstrap, consumerGroup)
        val perPartition = mutableListOf<PartitionScanResult>()
        try {
            val assignment = consumer.partitionsFor(topic, Duration.ofSeconds(5))
                ?.map { TopicPartition(it.topic(), it.partition()) }
                ?: emptyList()
            if (assignment.isEmpty()) {
                log.warn("[StaleKafka] no partitions for topic={}", topic)
                return ScanResult(topic, consumerGroup, keepRunIds, 0, 0, emptyMap(), emptyList())
            }
            consumer.assign(assignment)
            consumer.seekToBeginning(assignment)
            val beginning = assignment.associateWith { consumer.position(it) }
            consumer.seekToEnd(assignment)
            val end = assignment.associateWith { consumer.position(it) }
            for (tp in assignment) {
                val startOff = beginning[tp] ?: 0L
                val endOff = end[tp] ?: 0L
                if (endOff <= startOff) {
                    perPartition.add(PartitionScanResult(tp.partition(), startOff, endOff, 0, emptyList()))
                    continue
                }
                consumer.seek(tp, startOff)
                val seen = mutableListOf<ConsumerRecord<String, String>>()
                while (seen.size < scanLimitPerPartition && consumer.position(tp) < endOff) {
                    val recs = consumer.poll(Duration.ofMillis(200))
                    if (recs.isEmpty) break
                    recs.forEach { rec -> if (rec.partition() == tp.partition()) seen.add(rec) }
                }
                val stale = seen.mapNotNull { rec ->
                    val runId = runIdFromPayload(rec.value() ?: return@mapNotNull null)
                    if (runId != null && runId !in keepRunIds) {
                        StaleRecord(rec.partition(), rec.offset(), runId)
                    } else {
                        null
                    }
                }
                perPartition.add(PartitionScanResult(tp.partition(), startOff, endOff, seen.size, stale))
            }
        } finally {
            consumer.close(Duration.ofSeconds(5))
        }
        val total = perPartition.sumOf { it.staleRecords.size.toLong() }
        val recommended = perPartition
            .filter { it.staleRecords.isNotEmpty() }
            .associate { it.partition to (it.staleRecords.maxOf { r -> r.offset } + 1) }
        log.info(
            "[StaleKafka] scan topic={} group={} keepRunIds={} partitions={} stale={} recommendedReset={}",
            topic, consumerGroup, keepRunIds, perPartition.size, total, recommended,
        )
        return ScanResult(topic, consumerGroup, keepRunIds, perPartition.size, total, recommended, perPartition)
    }

    private fun newConsumer(bootstrap: String, group: String): KafkaConsumer<String, String> {
        val cfg = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(ConsumerConfig.GROUP_ID_CONFIG, group)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "200")
        }
        return KafkaConsumer(cfg)
    }

    private fun runIdFromPayload(payload: String): String? {
        val tree: JsonNode = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return null
        return tree.path("runId").asText(null)
            ?: tree.path("sourceRunId").asText(null)
    }
}

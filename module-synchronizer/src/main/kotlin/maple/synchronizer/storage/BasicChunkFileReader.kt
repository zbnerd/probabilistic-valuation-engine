package maple.synchronizer.storage

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

data class BasicRecord(
    val userIgn: String,
    val ocid: String,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int?,
    val guildName: String?,
    val compressedBody: ByteArray,
    val bodyHash: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Component
class BasicChunkFileReader(
    @Value("\${synchronizer.store.base-path:../data}")
    private val basePath: String,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
    @Qualifier("basicChunkMissingFieldThreshold")
    private val missingFieldThreshold: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEFAULT_BATCH_SIZE = 1000
    }

    fun read(objectKey: String): List<BasicRecord> {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        val records = mutableListOf<BasicRecord>()
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    parseRecord(line, parseErrors, missingFields, filtered)?.let { records.add(it) }
                }
                line = reader.readLine()
            }
        }
        logChunkSummary(objectKey, records.size, parseErrors.get(), missingFields.get(), filtered.get())
        return records
    }

    fun readInBatches(
        objectKey: String,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        handler: (List<BasicRecord>) -> Unit,
    ) {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            val batch = mutableListOf<BasicRecord>()
            val seenOcids = mutableSetOf<String>()
            var totalCount = 0
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val record = parseRecord(line, parseErrors, missingFields, filtered)
                    if (record != null && seenOcids.add(record.ocid)) {
                        batch.add(record)
                        if (batch.size >= batchSize) {
                            totalCount += batch.size
                            handler(batch.toList())
                            batch.clear()
                        }
                    }
                }
                line = reader.readLine()
            }
            if (batch.isNotEmpty()) {
                totalCount += batch.size
                handler(batch)
            }
            logChunkSummary(objectKey, totalCount, parseErrors.get(), missingFields.get(), filtered.get())
        }
    }

    private fun parseRecord(
        line: String,
        parseErrorCount: AtomicLong,
        missingFieldCount: AtomicLong,
        filteredCount: AtomicLong,
    ): BasicRecord? {
        val node: JsonNode = try {
            objectMapper.readTree(line)
        } catch (ex: JsonProcessingException) {
            parseErrorCount.incrementAndGet()
            readerMetrics.incrementParseError("basic_chunk")
            log.error("[BasicChunkFileReader] parse error at line: {}", line.take(80), ex)
            throw ex
        }

        val status = node.get("status")?.asText()
        if (status != "SUCCESS") {
            log.debug("skip record: reason=status_mismatch actual={}", status)
            filteredCount.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "status")
            return null
        }
        val endpoint = node.get("endpoint")?.asText()
        if (endpoint != "character-basic") {
            log.debug("skip record: reason=endpoint_mismatch actual={}", endpoint)
            filteredCount.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "endpoint")
            return null
        }

        val ocid = node.get("key")?.asText()
        if (ocid == null) {
            log.debug("skip record: reason=missing_ocid")
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFieldCount.get() > missingFieldThreshold) {
                throw IllegalStateException(
                    "BasicChunk missing-field threshold exceeded: $missingFieldCount > $missingFieldThreshold",
                )
            }
            return null
        }
        val body = node.get("body")
        if (body == null) {
            log.debug("skip record: reason=missing_body")
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFieldCount.get() > missingFieldThreshold) {
                throw IllegalStateException(
                    "BasicChunk missing-field threshold exceeded: $missingFieldCount > $missingFieldThreshold",
                )
            }
            return null
        }

        val userIgn = body.get("character_name")?.asText()
        if (userIgn == null) {
            log.debug("skip record: reason=missing_character_name")
            return null
        }
        val worldName = body.get("world_name")?.asText()
        val characterClass = body.get("character_class")?.asText()
        val characterLevel = body.get("character_level")?.asInt()
        val guildName = body.get("guild_name")?.asText()

        val bodyBytes = objectMapper.writeValueAsBytes(body)
        val compressed = GzipUtils.compress(bodyBytes)
        val hash = sha256Hex(bodyBytes)

        return BasicRecord(
            userIgn = userIgn,
            ocid = ocid,
            worldName = worldName,
            characterClass = characterClass,
            characterLevel = characterLevel,
            guildName = guildName,
            compressedBody = compressed,
            bodyHash = hash,
        )
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun logChunkSummary(
        objectKey: String,
        records: Int,
        parseErrors: Long,
        missingFields: Long,
        filtered: Long,
    ) {
        when {
            parseErrors > 0 -> log.error(
                "[BasicChunkFileReader] parseErrors={} missingFields={} filtered={} parsed={} from {}",
                parseErrors, missingFields, filtered, records, objectKey,
            )
            missingFields > 0 || filtered > 0 -> log.warn(
                "[BasicChunkFileReader] missingFields={} filtered={} parsed={} from {}",
                missingFields, filtered, records, objectKey,
            )
            else -> log.info("[BasicChunkFileReader] parsed {} records from {}", records, objectKey)
        }
    }
}

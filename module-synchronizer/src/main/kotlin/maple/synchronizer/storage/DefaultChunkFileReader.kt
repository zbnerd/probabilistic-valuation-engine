package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import maple.expectation.common.storage.ObjectStorage
import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.CalculatedEquipmentItem
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping
import maple.expectation.core.port.out.ChunkFileReaderPort
import maple.synchronizer.domain.BasicRecord as BasicRecordAlias
import maple.synchronizer.domain.CalculatedEquipmentItem as CalculatedEquipmentItemAlias
import maple.synchronizer.domain.GroupedEquipmentResult as GroupedEquipmentResultAlias
import maple.synchronizer.domain.OcidMapping as OcidMappingAlias
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.math.BigDecimal

/**
 * Consolidated chunk reader with IO/CPU 분리 (per VS2 spec §5.3).
 *
 * - IO (objectStorage.get) runs on Dispatchers.IO (VT-friendly for network)
 * - CPU (GZIP decompress + JSON parse + dedup) runs on Dispatchers.Default
 */
@Component
class DefaultChunkFileReader(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
    @Qualifier("basicChunkMissingFieldThreshold")
    private val missingFieldThreshold: Int,
) : ChunkFileReaderPort {

    override fun readBasicChunk(objectKey: String): List<BasicRecord> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(objectKey) }
        withContext(Dispatchers.Default) { parseBasicChunk(rawBytes, objectKey) }
    }

    override fun readResultChunk(objectKey: String): List<GroupedEquipmentResult> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(objectKey) }
        withContext(Dispatchers.Default) { parseResultChunk(rawBytes, objectKey) }
    }

    override fun readOcidMapping(manifestPath: String): List<OcidMapping> = runBlocking {
        val rawBytes = withContext(Dispatchers.IO) { objectStorage.get(manifestPath) }
        withContext(Dispatchers.Default) { parseOcidMapping(rawBytes, manifestPath) }
    }

    private fun parseBasicChunk(rawBytes: ByteArray, objectKey: String): List<BasicRecord> {
        val records = mutableListOf<BasicRecord>()
        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    parseBasicLine(line, objectKey, parseErrors, missingFields, filtered)
                        ?.let { records.add(it) }
                }
                line = reader.readLine()
            }
        }
        return records
    }

    private fun parseBasicLine(
        line: String,
        objectKey: String,
        parseErrors: AtomicLong,
        missingFields: AtomicLong,
        filtered: AtomicLong,
    ): BasicRecord? {
        val node = try {
            objectMapper.readTree(line)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            parseErrors.incrementAndGet()
            readerMetrics.incrementParseError("basic_chunk")
            throw ex
        }

        // Recent chunk writes carry the response body as a base64-encoded
        // ByteArray field `bodyBytes` (Jackson serializes ByteArray as
        // base64). Older writes inlined the body as a nested `body` JSON
        // object. Accept either shape so the sync does not silently drop
        // every record from the new format.
        val status = node.get("status")?.asText()
        val httpStatus = node.get("httpStatus")?.asInt(0) ?: 0
        val isSuccess = status == "SUCCESS" || (status.isNullOrBlank() && httpStatus == 200)
        if (!isSuccess) {
            filtered.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "status")
            return null
        }
        val endpoint = node.get("endpoint")?.asText()
        if (endpoint != "character-basic") {
            filtered.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "endpoint")
            return null
        }

        val ocid = node.get("key")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFields.get() > missingFieldThreshold) {
                throw IllegalStateException("BasicChunk missing-field threshold exceeded")
            }
            return null
        }
        val body = extractBody(node) ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFields.get() > missingFieldThreshold) {
                throw IllegalStateException("BasicChunk missing-field threshold exceeded")
            }
            return null
        }

        val userIgn = body.get("character_name")?.asText() ?: return null
        val worldName = body.get("world_name")?.takeIf { !it.isNull }?.asText()
        val characterClass = body.get("character_class")?.takeIf { !it.isNull }?.asText()
        val characterLevel = body.get("character_level")?.takeIf { !it.isNull }?.asInt()
        val guildName = body.get("guild_name")?.takeIf { !it.isNull }?.asText()

        val bodyBytes = objectMapper.writeValueAsBytes(body)
        return BasicRecord(
            userIgn = userIgn,
            ocid = ocid,
            worldName = worldName,
            characterClass = characterClass,
            characterLevel = characterLevel,
            guildName = guildName,
            compressedBody = maple.expectation.util.GzipUtils.compress(bodyBytes),
            bodyHash = maple.expectation.util.HashUtils.sha256Hex(bodyBytes),
        )
    }

    /**
     * Return the response body node, accepting both:
     *  - inline `body` JSON object (older writes)
     *  - `bodyBytes` (base64-encoded JSON bytes) — Jackson default for ByteArray
     *
     * Returns null if neither is present, or if bodyBytes base64 / JSON parse fails.
     */
    private fun extractBody(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode? {
        val inline = node.get("body")
        if (inline != null && !inline.isMissingNode && !inline.isNull) return inline
        val bodyBytesField = node.get("bodyBytes")
        if (bodyBytesField == null || bodyBytesField.isMissingNode || bodyBytesField.isNull) return null
        val b64 = bodyBytesField.asText("")
        if (b64.isBlank()) return null
        return runCatching {
            val raw = java.util.Base64.getDecoder().decode(b64)
            objectMapper.readTree(raw)
        }.getOrNull()
    }

    private fun parseResultChunk(rawBytes: ByteArray, objectKey: String): List<GroupedEquipmentResult> {
        val grouped = mutableMapOf<String, MutableList<CalculatedEquipmentItem>>()
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val item = parseResultLine(line, objectKey)
                    grouped.getOrPut("${item.ocid}:${item.presetNo}") { mutableListOf() }.add(item)
                }
                line = reader.readLine()
            }
        }
        return grouped.map { (readKey, group) ->
            GroupedEquipmentResult(
                readKey = readKey,
                ocid = group.first().ocid,
                presetNo = group.first().presetNo,
                items = group,
            )
        }
    }

    private fun parseResultLine(line: String, objectKey: String): CalculatedEquipmentItem {
        val node = objectMapper.readTree(line)
        return CalculatedEquipmentItem(
            ocid = requireNotNull(node.get("ocid")?.asText()) { "Missing required field: ocid" },
            presetNo = requireNotNull(node.get("presetNo")?.asInt()) { "Missing required field: presetNo" },
            itemName = node.get("itemName")?.asText() ?: "",
            itemLevel = node.get("itemLevel")?.asInt() ?: 0,
            itemPart = node.get("itemPart")?.asText() ?: "",
            itemEquipmentPart = node.get("itemEquipmentPart")?.asText(),
            potentialGrade = node.get("potentialGrade")?.asText(),
            potentialOptions = node.get("potentialOptions")?.map { it.asText() },
            additionalGrade = node.get("additionalGrade")?.asText(),
            additionalOptions = node.get("additionalOptions")?.map { it.asText() },
            currentStar = node.get("currentStar")?.asInt() ?: 0,
            targetStar = node.get("targetStar")?.asInt() ?: 0,
            status = node.get("status")?.asText() ?: "UNKNOWN",
            totalCost = node.get("totalCost")?.decimalValue() ?: BigDecimal.ZERO,
            blackCubeCost = node.get("blackCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
            additionalCubeCost = node.get("additionalCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
            starforceCost = node.get("starforceCost")?.decimalValue() ?: BigDecimal.ZERO,
            errorMessage = node.get("errorMessage")?.asText(),
        )
    }

    private fun parseOcidMapping(rawBytes: ByteArray, manifestPath: String): List<OcidMapping> {
        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    parseOcidMappingLine(line, manifestPath, parseErrors, missingFields)
                        ?.let { mappings.add(it) }
                }
        }
        return mappings
    }

    private fun parseOcidMappingLine(
        line: String,
        manifestPath: String,
        parseErrors: AtomicLong,
        missingFields: AtomicLong,
    ): OcidMapping? {
        val node = try {
            objectMapper.readTree(line)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            parseErrors.incrementAndGet()
            readerMetrics.incrementParseError("ocid_mapping")
            throw ex
        }
        val ign = node.get("userIgn")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        val ocid = node.get("ocid")?.asText() ?: run {
            missingFields.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        return OcidMapping(ign, ocid)
    }
}

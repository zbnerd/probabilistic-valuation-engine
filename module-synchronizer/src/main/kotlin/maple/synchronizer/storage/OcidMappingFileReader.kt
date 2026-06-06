package maple.synchronizer.storage

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

data class OcidMapping(
    val userIgn: String,
    val ocid: String,
)

@Component
class OcidMappingFileReader(
    @Value("\${synchronizer.store.base-path:../data}")
    private val storeBasePath: String,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(manifestPath: String): List<OcidMapping> {
        val path = Paths.get(storeBasePath, manifestPath)
        if (!Files.exists(path)) {
            throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "OcidMappingFileReader", manifestPath)
        }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    parseMapping(line, parseErrors, missingFields)?.let { mappings.add(it) }
                }
        }
        val pe = parseErrors.get()
        val mf = missingFields.get()
        when {
            pe > 0 -> log.error("[OcidMappingFileReader] parseErrors={} missingFields={} parsed={} from {}",
                pe, mf, mappings.size, manifestPath)
            mf > 0 -> log.warn("[OcidMappingFileReader] missingFields={} parsed={} from {}",
                mf, mappings.size, manifestPath)
            else -> log.info("[OcidMappingFileReader] parsed {} mappings from {}", mappings.size, manifestPath)
        }
        return mappings
    }

    private fun parseMapping(
        line: String,
        parseErrorCount: AtomicLong,
        missingFieldCount: AtomicLong,
    ): OcidMapping? {
        val node: JsonNode = try {
            objectMapper.readTree(line)
        } catch (ex: JsonProcessingException) {
            parseErrorCount.incrementAndGet()
            readerMetrics.incrementParseError("ocid_mapping")
            log.error("[OcidMappingFileReader] parse error at line: {}", line.take(80), ex)
            throw ex
        }
        val ign = node.get("userIgn")?.asText()
        if (ign == null) {
            log.debug("skip mapping: reason=missing_userIgn")
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        val ocid = node.get("ocid")?.asText()
        if (ocid == null) {
            log.debug("skip mapping: reason=missing_ocid")
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        return OcidMapping(ign, ocid)
    }
}

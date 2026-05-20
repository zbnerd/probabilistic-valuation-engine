package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(manifestPath: String): List<OcidMapping> {
        val path = Paths.get(storeBasePath, manifestPath)
        if (!Files.exists(path)) {
            log.warn("[OcidMappingFileReader] file not found: {}", path)
            return emptyList()
        }

        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line -> parseMapping(line)?.let { mappings.add(it) } }
        }
        log.info("[OcidMappingFileReader] parsed {} mappings from {}", mappings.size, manifestPath)
        return mappings
    }

    private fun parseMapping(line: String): OcidMapping? {
        return runCatching {
            val node = objectMapper.readTree(line)
            val ign = node.get("userIgn")?.asText() ?: return null
            val ocid = node.get("ocid")?.asText() ?: return null
            OcidMapping(ign, ocid)
        }.getOrNull()
    }
}

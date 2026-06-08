package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class OcidCacheProvider(
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.store.base-path:../data}") private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val mappingFile = findLatestOcidMappingFile()
        if (mappingFile == null) {
            log.info("[OcidCache] no ocid-mapping file found, keeping current cache")
            return cacheRef.get()
        }

        val loaded = loadFromGzipJsonl(mappingFile)
        cacheRef.set(loaded)
        log.info("[OcidCache] loaded: {} entries from {}", loaded.size, mappingFile.fileName)
        return loaded
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()

    private fun findLatestOcidMappingFile(): Path? {
        val dir = Path.of(storeBasePath, "ocid-mapping")
        if (!Files.isDirectory(dir)) {
            log.info("[OcidCache] directory not found: {}", dir)
            return null
        }

        Files.list(dir).use { stream ->
            val files = stream
                .filter { path -> path.toString().endsWith(".jsonl.gz") }
                .sorted()
                .toList()
            return files.lastOrNull()
        }
    }

    private fun loadFromGzipJsonl(path: Path): Map<String, String> {
        val cache = mutableMapOf<String, String>()
        GZIPInputStream(Files.newInputStream(path)).use { gzipIn ->
            BufferedReader(InputStreamReader(gzipIn, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        val node = objectMapper.readTree(line)
                        val userIgn = node.get("userIgn")?.asText()
                        val ocid = node.get("ocid")?.asText()
                        if (userIgn != null && ocid != null) {
                            cache[userIgn] = ocid
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
        return cache
    }
}

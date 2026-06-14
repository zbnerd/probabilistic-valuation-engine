package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * In-memory cache of userIgn → ocid, loaded from the latest
 * `ocid-mapping/ocid-mapping-*.jsonl.gz` object in ObjectStorage.
 * Picked by `ObjectInfo.lastModified` (max).
 *
 * Each line of the gzipped JSONL is a `{"userIgn":"...","ocid":"..."}` object
 * (matching the writer in [OcidLookupPhase.writeMappingGzipped]). The reader
 * wraps the stream in [GZIPInputStream] because the ObjectStorage impls do
 * not auto-decompress (same as the read path in [OcidLookupPhase]).
 */
@Component
class OcidCacheProvider(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val objects = objectStorage.listByPrefix("ocid-mapping/")
        val latest = objects.maxByOrNull { it.lastModified } ?: run {
            log.info("[OcidCache] no ocid-mapping objects found, cache remains empty")
            return emptyMap()
        }
        GZIPInputStream(BufferedInputStream(objectStorage.getStream(latest.key))).bufferedReader().useLines { lines ->
            val map = HashMap<String, String>()
            var parseErrors = 0
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = parseLine(line)
                if (entry != null) {
                    map[entry.first] = entry.second
                } else {
                    parseErrors++
                }
            }
            cacheRef.set(map)
            if (parseErrors > 0) {
                log.warn(
                    "[OcidCache] refreshed: {} entries from {} ({} parse errors ignored)",
                    map.size, latest.key, parseErrors,
                )
            } else {
                log.info("[OcidCache] refreshed: {} entries from {}", map.size, latest.key)
            }
            return map
        }
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val node = try {
            objectMapper.readTree(line)
        } catch (ex: Exception) {
            return null
        }
        val ign = node.get("userIgn")?.asText() ?: return null
        val ocid = node.get("ocid")?.asText() ?: return null
        if (ign.isBlank() || ocid.isBlank()) return null
        return ign to ocid
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()
}

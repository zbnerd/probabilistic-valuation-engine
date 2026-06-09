package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

@Component
class OcidCacheProvider(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val latest = findLatestOcidMapping()
        if (latest == null) {
            log.info("[OcidCache] no ocid-mapping file found, keeping current cache")
            return cacheRef.get()
        }

        // Issue #1128: CPU offload — GZIP decompress + per-line JSON parse on Dispatchers.Default.
        val loaded = loadFromGzipJsonlAsync(latest).join()
        cacheRef.set(loaded)
        log.info("[OcidCache] loaded: {} entries from {}", loaded.size, latest.key.substringAfterLast('/'))
        return loaded
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()

    private fun findLatestOcidMapping(): ObjectInfo? {
        val all = objectStorage.listByPrefix("ocid-mapping/")
        return all
            .filter { it.key.endsWith(".jsonl.gz") }
            .maxByOrNull { it.lastModified }
    }

    private fun loadFromGzipJsonl(info: ObjectInfo): Map<String, String> {
        val cache = mutableMapOf<String, String>()
        objectStorage.getStream(info.key).use { stream ->
            GZIPInputStream(stream).use { gzipIn ->
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
        }
        return cache
    }

    /**
     * Issue #1128: GZIP decompress + per-line JSON parse on `Dispatchers.Default`.
     * Caller may `.join()` (sync block) or chain via `.thenCompose()`.
     */
    private fun loadFromGzipJsonlAsync(info: ObjectInfo): CompletableFuture<Map<String, String>> =
        CompletableFuture.supplyAsync({
            loadFromGzipJsonl(info)
        }, Dispatchers.Default.asExecutor())
}

package maple.externalapi.cache

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import maple.common.parser.StreamingChunkParser
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache of userIgn → ocid, loaded from the latest
 * `ocid-mapping/ocid-mapping-*.jsonl.gz` object in ObjectStorage.
 * Picked by `ObjectInfo.lastModified` (max).
 *
 * Each record of the gzipped JSONL is a `{"userIgn":"...","ocid":"..."}`
 * object (matching the writer in [OcidLookupPhase.writeMappingGzipped]).
 *
 * Uses [StreamingChunkParser] for streaming parse (no intermediate
 * full materialization of the gz payload as a `List<String>` of lines).
 *
 * NOTE: This is a cold path (called once per OCID mapping refresh,
 * not in the per-record pipeline). `runBlocking` here bridges the
 * synchronous `refresh()` / `loadFromRun()` API surface; the call
 * sites are admin-trigger / startup-load, not request hot path.
 * Per project rule (async-patterns.md), `runBlocking` is forbidden in
 * request hot paths; this class is invoked only from non-VT scheduler
 * triggers, mirroring the pattern in `ExternalApiScheduler.kt:188`.
 */
@Component
class OcidCacheProvider(
    private val objectStorage: ObjectStorage,
    private val streamingChunkParser: StreamingChunkParser,
) {

    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())
    /**
     * Tracks the last successfully loaded key. Used by [loadFromKey] to
     * short-circuit repeat loads with the same key (e.g. ITEM_EQUIPMENT
     * loop calling `loadFromRun` once per iteration). See ADR-729.
     */
    private val loadedKey = AtomicReference<String?>(null)

    fun refresh(): Map<String, String> {
        val objects = objectStorage.listByPrefix("ocid-mapping/")
        val latest = objects.maxByOrNull { it.lastModified } ?: run {
            log.info("[OcidCache] no ocid-mapping objects found, cache remains empty")
            return emptyMap()
        }
        return loadFromKey(latest.key)
    }

    /**
     * Load OCID mapping from a specific prior run. Used by standalone
     * char-basic and item-equipment triggers to consume a known upstream's
     * OCID file rather than the most-recent one.
     * Key format: `ocid-mapping/ocid-mapping-{runId}.jsonl.gz`.
     */
    fun loadFromRun(runId: String): Map<String, String> =
        loadFromKey("ocid-mapping/ocid-mapping-$runId.jsonl.gz")

    private fun loadFromKey(key: String): Map<String, String> {
        // Read-through cache. If the same key was already loaded, return the
        // existing snapshot without re-streaming the JSONL.
        if (key == loadedKey.get()) {
            return cacheRef.get()
        }
        val map = HashMap<String, String>()
        var parseErrors = 0
        try {
            val records = runBlocking {
                objectStorage.getStream(key).use { stream ->
                    streamingChunkParser.parse(stream).toList()
                }
            }
            for (record in records) {
                val ign = record["userIgn"]?.toString()
                val ocid = record["ocid"]?.toString()
                if (ign.isNullOrBlank() || ocid.isNullOrBlank()) {
                    parseErrors++
                    continue
                }
                map[ign] = ocid
            }
            cacheRef.set(map)
            loadedKey.set(key)
            if (parseErrors > 0) {
                log.warn(
                    "[OcidCache] loaded key={}: {} entries ({} parse errors)",
                    key, map.size, parseErrors,
                )
            } else {
                log.info("[OcidCache] loaded key={}: {} entries", key, map.size)
            }
        } catch (ex: Exception) {
            log.error("[OcidCache] load failed key={}", key, ex)
            return emptyMap()
        }
        return map
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()
}

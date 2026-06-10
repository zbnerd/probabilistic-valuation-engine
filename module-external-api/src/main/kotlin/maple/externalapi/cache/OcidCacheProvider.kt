package maple.externalapi.cache

import maple.expectation.common.storage.ObjectStorage
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache of userIgn → ocid, loaded from the latest
 * `ocid-mapping/ocid-mapping-*.jsonl.gz` object in ObjectStorage.
 * Picked by `ObjectInfo.lastModified` (max).
 */
class OcidCacheProvider(private val objectStorage: ObjectStorage) {

    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val objects = objectStorage.listByPrefix("ocid-mapping/")
        val latest = objects.maxByOrNull { it.lastModified } ?: return emptyMap()
        objectStorage.getStream(latest.key).bufferedReader().useLines { lines ->
            val map = HashMap<String, String>()
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split("\t")
                if (parts.size >= 2) map[parts[0]] = parts[1]
            }
            cacheRef.set(map)
            return map
        }
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()
}

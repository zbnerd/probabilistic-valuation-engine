package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class OcidCacheProvider(
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val loaded = loadFromArtifacts()
        cacheRef.set(loaded)
        return loaded
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()

    private fun loadFromArtifacts(): Map<String, String> {
        val keys = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (keys.isEmpty()) {
            log.info("[OcidCache] no stored OCIDs found, cache empty")
            return emptyMap()
        }

        val cache = mutableMapOf<String, String>()
        for (key in keys) {
            val ocid = parseOcidFromArtifact(key)
            if (ocid != null) {
                cache[key] = ocid
            }
        }
        log.info("[OcidCache] loaded: {} entries", cache.size)
        return cache
    }

    private fun parseOcidFromArtifact(key: String): String? {
        val bytes = artifactStore.read(ExternalApiEndpoint.OCID_LOOKUP, key) ?: return null
        return parseOcidField(bytes, key)
    }

    private fun parseOcidField(bytes: ByteArray, key: String): String? =
        executor.executeOrDefault(
            { objectMapper.readTree(bytes).get("ocid")?.asText() },
            null,
            TaskContext.of("OcidCache", "ParseField", key),
        )
}

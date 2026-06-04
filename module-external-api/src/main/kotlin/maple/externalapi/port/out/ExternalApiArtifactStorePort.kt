package maple.externalapi.port.out

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef

interface ExternalApiArtifactStorePort {

    fun store(
        endpoint: ExternalApiEndpoint,
        key: String,
        data: ByteArray,
    ): ExternalApiPayloadRef

    fun read(
        endpoint: ExternalApiEndpoint,
        key: String,
    ): ByteArray

    fun listStoredKeys(endpoint: ExternalApiEndpoint): List<String>

    fun listRuns(): List<String>

    fun deleteRun(runId: String): Long

    fun deleteAll(endpoint: ExternalApiEndpoint): Int

    fun fileExists(relativePath: String): Boolean

    fun calculateDirectorySize(relativePath: String): Long
}

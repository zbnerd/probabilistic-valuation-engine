package maple.externalapi.port.out

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef

/**
 * @deprecated Use `maple.expectation.common.storage.ObjectStorage` (VS1) instead.
 *   This port is fully replaced by the unified ObjectStorage interface.
 *   Removal is planned in issue #1221.
 */
@Deprecated(
    message = "Replaced by maple.expectation.common.storage.ObjectStorage (VS1). " +
        "Use ObjectStorage instead. Removal planned in #1221.",
    replaceWith = ReplaceWith(
        "maple.expectation.common.storage.ObjectStorage",
        "maple.expectation.common.storage.ObjectStorage",
    ),
)
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

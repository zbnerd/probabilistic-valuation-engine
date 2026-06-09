package maple.expectation.infrastructure.external.snapshot

import maple.expectation.common.storage.ObjectStorage
import maple.expectation.common.storage.PutResult
import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.SnapshotObjectStoreResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Thin wrapper: SnapshotObjectStore port -> ObjectStorage unified.
 * 3 callers (ExternalApiWorker, NexonApiWorker, SnapshotCleanupWorker)
 * continue using SnapshotObjectStore port unchanged.
 *
 * storageType is determined at init from `storage.backend` and logged for
 * observability. CalculationSnapshot.storageType is populated by callers
 * (set on construction); this wrapper does not overwrite that field.
 */
@Component
class SnapshotObjectStoreAdapter(
    private val objectStorage: ObjectStorage,
    @Value("\${storage.backend:local}") private val storageBackend: String,
) : SnapshotObjectStore {

    private val log = LoggerFactory.getLogger(SnapshotObjectStoreAdapter::class.java)

    init {
        log.info("[SnapshotStore] active backend: storageType={}", activeStorageType())
    }

    override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val result: PutResult = objectStorage.put(snapshot.objectKey, data)
        return SnapshotObjectStoreResult(
            objectKey = result.key,
            compressedSize = result.size,
            // PutResult.checksum is String? (MinIO ETag may be null in edge cases
            // like single-byte put with multipart edge). Port's hash is non-null;
            // assert at runtime.
            hash = result.checksum ?: error(
                "ObjectStorage.put returned null checksum for key=${result.key}"
            ),
        )
    }

    override fun get(objectKey: String): ByteArray = objectStorage.get(objectKey)

    override fun delete(objectKey: String) = objectStorage.delete(objectKey)

    private fun activeStorageType(): String = when (storageBackend) {
        "minio" -> "S3"
        else -> "LOCAL"
    }
}

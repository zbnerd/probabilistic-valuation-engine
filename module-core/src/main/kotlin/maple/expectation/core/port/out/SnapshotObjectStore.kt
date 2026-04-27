package maple.expectation.core.port.out

import maple.expectation.core.model.snapshot.CalculationSnapshot

interface SnapshotObjectStore {
    fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult
    fun get(objectKey: String): ByteArray
    fun delete(objectKey: String)
}

data class SnapshotObjectStoreResult(
    val objectKey: String,
    val compressedSize: Long,
    val hash: String
)

package maple.pipeline.artifact.storage

import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CompletionStage
import maple.expectation.common.storage.ObjectInfo
import maple.expectation.common.storage.ObjectStorage
import maple.pipeline.artifact.identity.ArtifactKey
import maple.pipeline.artifact.identity.ArtifactPrefix

interface ConditionalObjectStorage : ObjectStorage {
    fun putIfAbsent(key: String, data: ByteArray): CompletionStage<PutIfAbsentResult>

    fun listPage(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int): StorageObjectPage
}

sealed interface PutIfAbsentResult {
    data class Created(val backendTag: String?) : PutIfAbsentResult

    data class Existing(val bytes: ByteArray, val backendTag: String?) : PutIfAbsentResult
}

class StorageObjectPage(
    objects: List<ObjectInfo>,
    val nextAfterKey: ArtifactKey?,
) {
    private val objectSnapshot: List<ObjectInfo> = Collections.unmodifiableList(ArrayList(objects))

    val objects: List<ObjectInfo>
        get() = objectSnapshot

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is StorageObjectPage &&
                objectSnapshot == other.objectSnapshot &&
                nextAfterKey == other.nextAfterKey
            )

    override fun hashCode(): Int = 31 * objectSnapshot.hashCode() + (nextAfterKey?.hashCode() ?: 0)

    override fun toString(): String = "StorageObjectPage(objects=$objectSnapshot, nextAfterKey=$nextAfterKey)"
}

internal fun validatePageRequest(prefix: ArtifactPrefix, afterKey: ArtifactKey?, limit: Int) {
    require(limit in 1..1_000) { "storage page limit must be in 1..1000" }
    require(afterKey == null || afterKey.value.startsWith(prefix.value)) {
        "storage page cursor must be a descendant of prefix '${prefix.value}'"
    }
}

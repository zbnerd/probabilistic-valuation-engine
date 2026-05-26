package maple.externalapi.snapshot

import java.time.Instant

sealed interface SnapshotChunkRecord {
    val key: String
    val endpoint: String
    val keyType: String
    val httpStatus: Int
    val fetchedAt: Instant

    data class Success(
        override val key: String,
        override val endpoint: String,
        override val keyType: String,
        override val httpStatus: Int,
        override val fetchedAt: Instant,
        val bodyBytes: ByteArray,
    ) : SnapshotChunkRecord {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Failure(
        override val key: String,
        override val endpoint: String,
        override val keyType: String,
        override val httpStatus: Int,
        override val fetchedAt: Instant,
        val errorMessage: String,
    ) : SnapshotChunkRecord

    data object CloseSignal : SnapshotChunkRecord {
        override val key: String = ""
        override val endpoint: String = ""
        override val keyType: String = ""
        override val httpStatus: Int = 0
        override val fetchedAt: Instant = Instant.EPOCH
    }
}

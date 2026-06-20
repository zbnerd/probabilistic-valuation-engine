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

    /**
     * Producer-serialized snapshot record. The caller (typically a virtual
     * thread inside [maple.externalapi.scheduler.phase.BatchFetchSupport])
     * has already invoked `ObjectMapper.writeValueAsBytes` on the equivalent
     * [Success]; the sink writer thread only does `GZIPOutputStream.write`
     * + disk, no Jackson. See ADR-729.
     *
     * `bodyBytes` carries the serialized JSON line including the trailing
     * newline already appended by the producer. Kept as a top-level field
     * (not inside a wrapper) so the writer can pass it to gzip without
     * per-record copying.
     */
    data class PreSerialized(
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

    data object CloseSignal : SnapshotChunkRecord {
        override val key: String = ""
        override val endpoint: String = ""
        override val keyType: String = ""
        override val httpStatus: Int = 0
        override val fetchedAt: Instant = Instant.EPOCH
    }
}

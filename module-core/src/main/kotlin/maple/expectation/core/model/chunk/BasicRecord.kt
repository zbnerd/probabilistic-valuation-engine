package maple.expectation.core.model.chunk

data class BasicRecord(
    val userIgn: String,
    val ocid: String,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int?,
    val guildName: String?,
    val compressedBody: ByteArray,
    val bodyHash: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

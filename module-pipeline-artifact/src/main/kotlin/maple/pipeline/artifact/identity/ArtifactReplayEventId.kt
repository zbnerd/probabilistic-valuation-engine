package maple.pipeline.artifact.identity

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object ArtifactReplayEventId {
    private val dnsNamespace: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    fun forChunk(
        eventType: String,
        runId: String,
        endpoint: String,
        chunkId: String,
    ): UUID {
        val validatedEventType = ArtifactSegment.require(eventType)
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        val validatedChunkId = ArtifactSegment.require(chunkId)
        val name = "pipeline-artifact:${validatedEventType.value}:${validatedRunId.value}:" +
            "${validatedEndpoint.value}:${validatedChunkId.value}"

        return uuidV5(name)
    }

    fun forRun(eventType: String, runId: String, endpoint: String): UUID {
        val validatedEventType = ArtifactSegment.require(eventType)
        val validatedRunId = ArtifactSegment.require(runId)
        val validatedEndpoint = ArtifactSegment.require(endpoint)
        val name = "pipeline-artifact:${validatedEventType.value}:${validatedRunId.value}:" +
            "${validatedEndpoint.value}:run"

        return uuidV5(name)
    }

    private fun uuidV5(name: String): UUID {
        val namespaceBytes = ByteBuffer.allocate(UUID_BYTES)
            .putLong(dnsNamespace.mostSignificantBits)
            .putLong(dnsNamespace.leastSignificantBits)
            .array()
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(namespaceBytes + name.toByteArray(StandardCharsets.UTF_8))

        hash[VERSION_BYTE_INDEX] = (
            (hash[VERSION_BYTE_INDEX].toInt() and VERSION_CLEAR_MASK) or VERSION_FIVE_BITS
        ).toByte()
        hash[VARIANT_BYTE_INDEX] = (
            (hash[VARIANT_BYTE_INDEX].toInt() and VARIANT_CLEAR_MASK) or RFC_4122_VARIANT_BITS
        ).toByte()

        val uuidBytes = ByteBuffer.wrap(hash.copyOf(UUID_BYTES))
        return UUID(uuidBytes.long, uuidBytes.long)
    }
}

private const val UUID_BYTES: Int = 16
private const val VERSION_BYTE_INDEX: Int = 6
private const val VARIANT_BYTE_INDEX: Int = 8
private const val VERSION_CLEAR_MASK: Int = 0x0f
private const val VERSION_FIVE_BITS: Int = 0x50
private const val VARIANT_CLEAR_MASK: Int = 0x3f
private const val RFC_4122_VARIANT_BITS: Int = 0x80

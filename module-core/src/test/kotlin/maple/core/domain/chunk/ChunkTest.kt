package maple.core.domain.chunk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChunkTest {
    @Test
    fun `holds input data and metadata`() {
        val input = ChunkProcessInput(
            objectKey = "key1",
            sourceRunId = "run1",
            sourceChunkId = "chunk1",
            resultCount = 42,
        )
        val chunk = Chunk(input = input, data = "payload", metadata = mapOf("trace" to "abc"))
        assertEquals(input, chunk.input)
        assertEquals("payload", chunk.data)
        assertEquals("abc", chunk.metadata["trace"])
    }

    @Test
    fun `metadata defaults to empty`() {
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, Unit)
        assertEquals(emptyMap<String, String>(), chunk.metadata)
    }
}

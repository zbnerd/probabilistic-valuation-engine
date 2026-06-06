package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChunkTransformerTest {
    @Test
    fun `transform maps data and propagates input`() = runTest {
        val transformer = object : ChunkTransformer<String, Int> {
            override suspend fun transform(chunk: Chunk<String>): Chunk<Int> =
                Chunk(chunk.input, chunk.data.length, chunk.metadata)
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "hello")
        val result = transformer.transform(chunk)
        assertEquals(5, result.data)
        assertEquals(input, result.input)
    }

    @Test
    fun `transform passes metadata through`() = runTest {
        val transformer = object : ChunkTransformer<String, String> {
            override suspend fun transform(chunk: Chunk<String>): Chunk<String> =
                Chunk(chunk.input, chunk.data.uppercase(), chunk.metadata)
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "abc", metadata = mapOf("step" to "1"))
        val result = transformer.transform(chunk)
        assertEquals("ABC", result.data)
        assertEquals("1", result.metadata["step"])
    }
}

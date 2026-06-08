package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChunkReaderTest {
    @Test
    fun `read returns chunk with payload`() = runTest {
        val reader = object : ChunkReader<String> {
            override suspend fun read(chunk: Chunk<Unit>): Chunk<String> = Chunk(chunk.input, "loaded", chunk.metadata)
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val initial = Chunk<Unit>(input, Unit)
        val result = reader.read(initial)
        assertEquals("loaded", result.data)
        assertEquals(input, result.input)
    }
}

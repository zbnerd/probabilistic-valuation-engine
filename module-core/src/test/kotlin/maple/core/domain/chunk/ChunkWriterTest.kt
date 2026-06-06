package maple.core.domain.chunk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.atomic.AtomicReference

class ChunkWriterTest {
    @Test
    fun `write receives chunk and returns terminal chunk`() = runTest {
        val captured = AtomicReference<Chunk<String>?>()
        val writer = object : ChunkWriter<String> {
            override suspend fun write(chunk: Chunk<String>): Chunk<Unit> {
                captured.set(chunk)
                return Chunk(chunk.input, Unit, chunk.metadata)
            }
        }
        val input = ChunkProcessInput("k", "r", "c", 0)
        val chunk = Chunk(input, "payload")
        val result = writer.write(chunk)
        assertEquals("payload", captured.get()?.data)
        assertEquals(Unit, result.data)
    }
}

package maple.synchronizer.event

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ResultChunkEventPathBuilderTest {
    private val builder = ResultChunkEventPathBuilder()

    @Test
    fun `sourceObjectKey produces runs path template`() {
        val actual = builder.sourceObjectKey(
            runId = "run-1",
            sourceEndpoint = "character-basic",
            chunkId = "chunk-42",
        )
        assertEquals("runs/run-1/character-basic/chunks/chunk-42.jsonl.gz", actual)
    }
}

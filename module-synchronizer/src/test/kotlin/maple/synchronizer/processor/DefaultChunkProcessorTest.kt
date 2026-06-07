package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DefaultChunkProcessorTest {

    private val orchestrator: ChunkPipelineOrchestrator = mock()
    private lateinit var chunkProcessor: DefaultChunkProcessor

    @BeforeEach
    fun setUp() {
        chunkProcessor = DefaultChunkProcessor(orchestrator)
    }

    @Test
    fun `process - delegates to orchestrator and returns its result`() {
        val input = testInput()
        val expected = ChunkProcessResult(documentCount = 5, itemCount = 9, jsonRowCount = 100L)
        whenever(orchestrator.execute(any())).thenReturn(expected)

        val result = chunkProcessor.process(input)

        assertThat(result).isSameAs(expected)
        verify(orchestrator).execute(input)
    }

    @Test
    fun `process - propagates ArtifactNotFoundException from orchestrator`() {
        val input = testInput()
        whenever(orchestrator.execute(any()))
            .thenThrow(ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ResultFileReader", "/tmp/missing"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(ArtifactNotFoundException::class.java)
            .hasMessageContaining("ResultFileReader")
    }

    @Test
    fun `process - propagates RuntimeException from orchestrator`() {
        val input = testInput()
        whenever(orchestrator.execute(any())).thenThrow(RuntimeException("DB connection failed"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB connection failed")
    }

    @Test
    fun `process - calls execute exactly once per process call`() {
        val input = testInput()
        whenever(orchestrator.execute(any())).thenReturn(ChunkProcessResult(0, 0, 0L))

        chunkProcessor.process(input)
        chunkProcessor.process(input)

        verify(orchestrator, times(2)).execute(input)
    }

    private fun testInput() = ChunkProcessInput(
        objectKey = "run1/chunk001.jsonl.gz",
        sourceRunId = "run-1",
        sourceChunkId = "chunk-001",
        resultCount = 1,
    )
}

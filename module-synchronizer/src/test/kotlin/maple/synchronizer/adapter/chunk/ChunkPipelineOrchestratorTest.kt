package maple.synchronizer.adapter.chunk

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.DocumentVolumeMetrics
import maple.synchronizer.preparer.PreppedDocument
import maple.synchronizer.processor.ChunkDataReader
import maple.synchronizer.processor.ChunkDocumentTransformer
import maple.synchronizer.processor.ChunkDocumentWriter
import maple.synchronizer.processor.TransformResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class ChunkPipelineOrchestratorTest {

    private val dataReader: ChunkDataReader = mock()
    private val transformer: ChunkDocumentTransformer = mock()
    private val writer: ChunkDocumentWriter = mock()
    private val volumeMetrics: DocumentVolumeMetrics = mock()

    private lateinit var orchestrator: ChunkPipelineOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = ChunkPipelineOrchestrator(dataReader, transformer, writer, volumeMetrics)
    }

    @Test
    fun `execute - happy path returns result with correct counts`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = orchestrator.execute(input)

        assertThat(result.documentCount).isEqualTo(1)
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.jsonRowCount).isEqualTo(input.resultCount.toLong())
    }

    @Test
    fun `execute - calls stages in order read then transform then write`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(1, 1, prepped))

        orchestrator.execute(input)

        verify(dataReader).read(eq(input.objectKey))
        verify(transformer).transform(eq(input.sourceRunId), eq(input.sourceChunkId), eq(grouped))
        verify(writer).write(eq(input.sourceRunId), eq(input.sourceChunkId), eq(prepped))
    }

    @Test
    fun `execute - records aggregate metrics for documents and items`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock(), mock(), mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(documentCount = 3, itemCount = 7, prepped = prepped))

        orchestrator.execute(input)

        verify(volumeMetrics).incrementDocuments(3)
        verify(volumeMetrics).incrementItems(7L)
        verify(volumeMetrics).recordChunkSize(3, 7L)
        verify(volumeMetrics, org.mockito.kotlin.times(3))
            .recordDocumentEquipment(org.mockito.kotlin.any())
    }

    @Test
    fun `execute - propagates exception from reader`() {
        val input = testInput()
        val ex = RuntimeException("file read failed")
        whenever(dataReader.read(any())).thenThrow(ex)

        assertThatThrownBy { orchestrator.execute(input) }.isSameAs(ex)
    }

    @Test
    fun `execute - propagates exception from writer`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(1, 1, prepped))
        val ex = RuntimeException("DB connection failed")
        whenever(writer.write(any(), any(), any())).thenThrow(ex)

        assertThatThrownBy { orchestrator.execute(input) }.isSameAs(ex)
    }

    @Test
    fun `execute - transformer exception prevents writer and skips metrics`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val ex = RuntimeException("transform failed")
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenThrow(ex)

        val thrown = runCatching { orchestrator.execute(input) }.exceptionOrNull()

        assertThat(thrown).isSameAs(ex)
        verify(writer, org.mockito.kotlin.never()).write(any(), any(), any())
        verify(volumeMetrics, org.mockito.kotlin.never()).incrementDocuments(any())
        verify(volumeMetrics, org.mockito.kotlin.never()).incrementItems(any())
        verify(volumeMetrics, org.mockito.kotlin.never()).recordChunkSize(any(), any())
        verify(volumeMetrics, org.mockito.kotlin.never()).recordDocumentEquipment(any())
    }

    @Test
    fun `execute - empty prepped list records zero metrics and returns zero counts`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val transformResult = TransformResult(documentCount = 0, itemCount = 0, prepped = emptyList())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = orchestrator.execute(input)

        assertThat(result.documentCount).isEqualTo(0)
        assertThat(result.itemCount).isEqualTo(0L)
        verify(volumeMetrics).incrementDocuments(0)
        verify(volumeMetrics).incrementItems(0L)
        verify(volumeMetrics).recordChunkSize(0, 0L)
        verify(volumeMetrics, org.mockito.kotlin.never()).recordDocumentEquipment(any())
        verify(writer).write(any(), any(), any())
    }

    private fun testInput(
        objectKey: String = "run1/chunk001.jsonl.gz",
        resultCount: Int = 1,
    ) = ChunkProcessInput(
        objectKey = objectKey,
        sourceRunId = "run-1",
        sourceChunkId = "chunk-001",
        resultCount = resultCount,
    )

    private fun testGrouped() = GroupedEquipmentResult(
        readKey = "oc1:1",
        ocid = "oc1",
        presetNo = 1,
        items = listOf(testItem()),
    )

    private fun testItem() = CalculatedEquipmentItem(
        ocid = "oc1",
        presetNo = 1,
        itemName = "Test Sword",
        itemLevel = 160,
        itemPart = "Weapon",
        itemEquipmentPart = "무기",
        potentialGrade = "레전드리",
        potentialOptions = listOf("공격력 +12%"),
        additionalGrade = "에픽",
        additionalOptions = listOf("STR +9%"),
        currentStar = 17,
        targetStar = 22,
        status = "SUCCESS",
        totalCost = BigDecimal("150000000000"),
        blackCubeCost = BigDecimal("50000000000"),
        additionalCubeCost = BigDecimal("30000000000"),
        starforceCost = BigDecimal("70000000000"),
        errorMessage = null,
    )
}

package maple.synchronizer.processor

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.PreppedDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class DefaultChunkProcessorTest {

    private val dataReader: ChunkDataReader = mock()
    private val transformer: ChunkDocumentTransformer = mock()
    private val writer: ChunkDocumentWriter = mock()
    private val metrics = SynchronizerMetrics(SimpleMeterRegistry())

    private lateinit var chunkProcessor: DefaultChunkProcessor

    @BeforeEach
    fun setUp() {
        chunkProcessor = DefaultChunkProcessor(dataReader, transformer, writer, metrics)
    }

    @Test
    fun `process - happy path returns result with correct counts`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = chunkProcessor.process(input)

        assertThat(result.documentCount).isEqualTo(1)
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.jsonRowCount).isEqualTo(input.resultCount.toLong())
    }

    @Test
    fun `process - calls writer with prepped documents`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        chunkProcessor.process(input)

        verify(writer).write(eq(input.sourceRunId), eq(input.sourceChunkId), eq(prepped))
    }

    @Test
    fun `process - file not found propagates exception`() {
        val input = testInput()
        whenever(dataReader.read(any()))
            .thenThrow(IllegalStateException("Result file not found"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Result file not found")
    }

    @Test
    fun `process - upsert failure propagates exception`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)
        doThrow(RuntimeException("DB connection failed"))
            .whenever(writer).write(any(), any(), any())

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB connection failed")
    }

    @Test
    fun `process - multiple groups produce multiple documents`() {
        val input = testInput(resultCount = 3)
        val grouped = listOf(
            GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem(ocid = "oc1"))),
            GroupedEquipmentResult(readKey = "oc2:1", ocid = "oc2", presetNo = 1, items = listOf(testItem(ocid = "oc2"), testItem(ocid = "oc2"))),
        )
        val prepped = listOf<PreppedDocument>(mock(), mock())
        val transformResult = TransformResult(documentCount = 2, itemCount = 3, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = chunkProcessor.process(input)

        assertThat(result.documentCount).isEqualTo(2)
        assertThat(result.itemCount).isEqualTo(3)
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

    private fun testItem(
        ocid: String = "oc1",
        presetNo: Int = 1,
    ) = CalculatedEquipmentItem(
        ocid = ocid,
        presetNo = presetNo,
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

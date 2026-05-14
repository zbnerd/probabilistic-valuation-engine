package maple.synchronizer.processor

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.builder.EquipmentDocumentBuilder
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.EquipmentReadDocument
import maple.synchronizer.domain.EquipmentReadMetadata
import maple.synchronizer.domain.EquipmentSummary
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.EquipmentDocumentPreparer
import maple.synchronizer.preparer.PreppedDocument
import maple.synchronizer.repository.EquipmentReadModelRepository
import maple.synchronizer.storage.ResultFileReader
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
import java.sql.Timestamp
import java.time.Instant

class DefaultChunkProcessorTest {

    private val resultFileReader: ResultFileReader = mock()
    private val documentBuilder: EquipmentDocumentBuilder = mock()
    private val preparer: EquipmentDocumentPreparer = mock()
    private val readModelRepository: EquipmentReadModelRepository = mock()
    private val metrics = SynchronizerMetrics(SimpleMeterRegistry())

    private lateinit var chunkProcessor: DefaultChunkProcessor

    @BeforeEach
    fun setUp() {
        chunkProcessor = DefaultChunkProcessor(resultFileReader, documentBuilder, preparer, readModelRepository, metrics)
    }

    @Test
    fun `process - happy path returns result with correct counts`() {
        val event = testEvent()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))

        whenever(resultFileReader.readAndGroupByCompositeKey(any())).thenReturn(grouped)
        whenever(documentBuilder.build(any(), any(), any())).thenReturn(testDocument())
        whenever(preparer.prepare(any())).thenReturn(listOf(testPreppedDocument()))

        val result = chunkProcessor.process(event)

        assertThat(result.documentCount).isEqualTo(1)
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.compressedBytes).isEqualTo(event.compressedBytes)
        assertThat(result.uncompressedBytes).isEqualTo(event.uncompressedBytes)
        assertThat(result.jsonRowCount).isEqualTo(event.resultCount.toLong())
    }

    @Test
    fun `process - calls preparer then repository bulkUpsert`() {
        val event = testEvent()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf(testPreppedDocument())

        whenever(resultFileReader.readAndGroupByCompositeKey(any())).thenReturn(grouped)
        whenever(documentBuilder.build(any(), any(), any())).thenReturn(testDocument())
        whenever(preparer.prepare(any())).thenReturn(prepped)

        chunkProcessor.process(event)

        verify(preparer).prepare(any())
        verify(readModelRepository).bulkUpsert(eq(event.sourceRunId), eq(event.sourceChunkId), eq(prepped))
    }

    @Test
    fun `process - file not found propagates exception`() {
        val event = testEvent()
        whenever(resultFileReader.readAndGroupByCompositeKey(any()))
            .thenThrow(IllegalStateException("Result file not found"))

        assertThatThrownBy { chunkProcessor.process(event) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Result file not found")
    }

    @Test
    fun `process - upsert failure propagates exception`() {
        val event = testEvent()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))

        whenever(resultFileReader.readAndGroupByCompositeKey(any())).thenReturn(grouped)
        whenever(documentBuilder.build(any(), any(), any())).thenReturn(testDocument())
        whenever(preparer.prepare(any())).thenReturn(listOf(testPreppedDocument()))
        whenever(readModelRepository.bulkUpsert(any(), any(), any()))
            .thenThrow(RuntimeException("DB connection failed"))

        assertThatThrownBy { chunkProcessor.process(event) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB connection failed")
    }

    @Test
    fun `process - multiple groups produce multiple documents`() {
        val event = testEvent(objectKey = "multi.jsonl.gz", resultCount = 3)
        val grouped = listOf(
            GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem(ocid = "oc1"))),
            GroupedEquipmentResult(readKey = "oc2:1", ocid = "oc2", presetNo = 1, items = listOf(testItem(ocid = "oc2"), testItem(ocid = "oc2"))),
        )

        whenever(resultFileReader.readAndGroupByCompositeKey(any())).thenReturn(grouped)
        whenever(documentBuilder.build(any(), any(), any())).thenReturn(testDocument())
        whenever(preparer.prepare(any())).thenReturn(listOf(testPreppedDocument(), testPreppedDocument(readKey = "oc2:1")))

        val result = chunkProcessor.process(event)

        assertThat(result.documentCount).isEqualTo(2)
        assertThat(result.itemCount).isEqualTo(3)
    }

    private fun testEvent(
        objectKey: String = "run1/chunk001.jsonl.gz",
        resultCount: Int = 1,
    ) = maple.expectation.common.event.CalculatorResultChunkReadyEvent(
        eventId = "evt-1",
        eventType = "CALCULATOR_RESULT_CHUNK_READY",
        schemaVersion = 1,
        sourceRunId = "run-1",
        sourceEndpoint = "ITEM_EQUIPMENT",
        sourceChunkId = "chunk-001",
        objectKey = objectKey,
        sourceRecordCount = resultCount,
        resultCount = resultCount,
        errorCount = 0,
        uncompressedBytes = 2048L,
        compressedBytes = 512L,
        createdAt = Instant.now(),
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

    private fun testDocument(
        ocid: String = "oc1",
    ) = EquipmentReadDocument(
        ocid = ocid,
        presetNo = 1,
        summary = EquipmentSummary(totalCost = BigDecimal("150000000000"), equipmentCount = 1),
        equipment = listOf(mapOf("itemName" to "Test Sword")),
        metadata = EquipmentReadMetadata(sourceRunId = "run-1", sourceChunkId = "chunk-001", calculatedAt = Instant.now()),
    )

    private fun testPreppedDocument(
        readKey: String = "oc1:1",
    ) = PreppedDocument(
        readKey = readKey,
        ocid = "oc1",
        presetNo = 1,
        compressed = ByteArray(0),
        documentHash = "abc123",
        totalCost = BigDecimal("150000000000"),
        equipmentCount = 1,
        calculatedAt = Timestamp.from(Instant.now()),
    )
}

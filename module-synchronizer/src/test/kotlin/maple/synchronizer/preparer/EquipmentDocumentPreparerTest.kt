package maple.synchronizer.preparer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.synchronizer.domain.EquipmentReadDocument
import maple.synchronizer.domain.EquipmentReadMetadata
import maple.synchronizer.domain.EquipmentSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class EquipmentDocumentPreparerTest {

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val preparer = EquipmentDocumentPreparer(objectMapper)

    @Test
    fun `prepare - serializes document and produces valid prepped document`() {
        val doc = testDocument()
        val result = preparer.prepare(listOf(doc))

        assertThat(result).hasSize(1)
        val prepped = result.first()
        assertThat(prepped.readKey).isEqualTo("oc1:1")
        assertThat(prepped.ocid).isEqualTo("oc1")
        assertThat(prepped.presetNo).isEqualTo(1.toShort())
        assertThat(prepped.documentHash).hasSize(64) // SHA-256 hex
        assertThat(prepped.compressed).isNotEmpty()
        assertThat(prepped.totalCost).isEqualByComparingTo(BigDecimal("150000000000"))
        assertThat(prepped.equipmentCount).isEqualTo(1)
    }

    @Test
    fun `prepare - different documents produce different hashes`() {
        val doc1 = testDocument(ocid = "oc1")
        val doc2 = testDocument(ocid = "oc2")

        val result = preparer.prepare(listOf(doc1, doc2))

        assertThat(result).hasSize(2)
        assertThat(result[0].documentHash).isNotEqualTo(result[1].documentHash)
    }

    @Test
    fun `prepare - same document produces same hash`() {
        val doc = testDocument()

        val result = preparer.prepare(listOf(doc, doc))

        assertThat(result[0].documentHash).isEqualTo(result[1].documentHash)
    }

    @Test
    fun `prepare - empty list returns empty list`() {
        val result = preparer.prepare(emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `prepare - compressed data can be decompressed back to JSON`() {
        val doc = testDocument()
        val result = preparer.prepare(listOf(doc))

        val json = objectMapper.writeValueAsString(doc)
        val decompressed = maple.expectation.util.GzipUtils.decompress(result.first().compressed)
        assertThat(decompressed).isEqualTo(json)
    }

    private fun testDocument(
        ocid: String = "oc1",
    ) = EquipmentReadDocument(
        ocid = ocid,
        presetNo = 1,
        summary = EquipmentSummary(totalCost = BigDecimal("150000000000"), equipmentCount = 1),
        equipment = listOf(mapOf("itemName" to "Test Sword", "status" to "SUCCESS")),
        metadata = EquipmentReadMetadata(sourceRunId = "run-1", sourceChunkId = "chunk-001", calculatedAt = Instant.parse("2026-05-14T00:00:00Z")),
    )
}

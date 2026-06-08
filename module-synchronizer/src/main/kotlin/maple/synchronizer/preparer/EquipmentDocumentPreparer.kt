package maple.synchronizer.preparer

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import maple.expectation.util.GzipUtils
import maple.expectation.util.HashUtils
import maple.synchronizer.domain.EquipmentReadDocument

class EquipmentDocumentPreparer(private val objectMapper: ObjectMapper) {

    fun prepare(documents: List<EquipmentReadDocument>): List<PreppedDocument> = documents.map { prepareOne(it) }

    private fun prepareOne(doc: EquipmentReadDocument): PreppedDocument {
        val bytes = objectMapper.writeValueAsBytes(doc)
        return PreppedDocument(
            readKey = "${doc.ocid}:${doc.presetNo}",
            ocid = doc.ocid,
            presetNo = doc.presetNo.toShort(),
            userIgn = doc.userIgn,
            compressed = GzipUtils.compress(bytes),
            documentHash = HashUtils.sha256Hex(bytes),
            totalCost = doc.summary.totalCost,
            equipmentCount = doc.summary.equipmentCount,
            calculatedAt = Timestamp.from(doc.metadata.calculatedAt),
        )
    }
}

data class PreppedDocument(
    val readKey: String,
    val ocid: String,
    val presetNo: Short,
    val userIgn: String?,
    val compressed: ByteArray,
    val documentHash: String,
    val totalCost: java.math.BigDecimal,
    val equipmentCount: Int,
    val calculatedAt: Timestamp,
)

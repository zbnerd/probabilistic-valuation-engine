package maple.synchronizer.preparer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import maple.synchronizer.domain.EquipmentReadDocument
import java.security.MessageDigest
import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class EquipmentDocumentPreparer(private val objectMapper: ObjectMapper) {

    fun prepare(documents: List<EquipmentReadDocument>): List<PreppedDocument> {
        return documents.map { prepareOne(it) }
    }

    private fun prepareOne(doc: EquipmentReadDocument): PreppedDocument {
        val json = objectMapper.writeValueAsString(doc)
        return PreppedDocument(
            readKey = "${doc.ocid}:${doc.presetNo}",
            ocid = doc.ocid,
            presetNo = doc.presetNo.toShort(),
            compressed = GzipUtils.compress(json),
            documentHash = sha256Hex(json),
            totalCost = doc.summary.totalCost,
            equipmentCount = doc.summary.equipmentCount,
            calculatedAt = Timestamp.from(doc.metadata.calculatedAt),
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class PreppedDocument(
    val readKey: String,
    val ocid: String,
    val presetNo: Short,
    val compressed: ByteArray,
    val documentHash: String,
    val totalCost: java.math.BigDecimal,
    val equipmentCount: Int,
    val calculatedAt: Timestamp,
)

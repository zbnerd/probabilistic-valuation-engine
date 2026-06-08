package maple.synchronizer.preparer

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import maple.expectation.util.GzipUtils
import maple.expectation.util.HashUtils
import maple.synchronizer.domain.EquipmentReadDocument

class EquipmentDocumentPreparer(private val objectMapper: ObjectMapper) {

    // Issue #1129: CPU offload — serialize + GZIP + SHA-256 on Dispatchers.Default.
    fun prepare(documents: List<EquipmentReadDocument>): List<PreppedDocument> = runBlocking(Dispatchers.Default) {
        documents.map { prepareOne(it) }
    }

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

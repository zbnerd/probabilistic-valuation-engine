package maple.synchronizer.builder

import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.EquipmentReadDocument
import maple.synchronizer.domain.EquipmentReadMetadata
import maple.synchronizer.domain.EquipmentSummary
import maple.synchronizer.domain.GroupedEquipmentResult
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class EquipmentDocumentBuilder {

    fun build(runId: String, chunkId: String, grouped: GroupedEquipmentResult): EquipmentReadDocument {
        val totalCost = grouped.items.fold(BigDecimal.ZERO) { acc, item -> acc + item.totalCost }
        val equipmentCount = grouped.items.count { it.status != "SKIPPED" }

        return EquipmentReadDocument(
            ocid = grouped.ocid,
            presetNo = grouped.presetNo,
            summary = EquipmentSummary(
                totalCost = totalCost,
                equipmentCount = equipmentCount,
            ),
            equipment = grouped.items.map { it.toMap() },
            metadata = EquipmentReadMetadata(
                sourceRunId = runId,
                sourceChunkId = chunkId,
                calculatedAt = Instant.now(),
            ),
        )
    }

    private fun CalculatedEquipmentItem.toMap(): Map<String, Any?> = mapOf(
        "itemName" to itemName,
        "itemLevel" to itemLevel,
        "itemPart" to itemPart,
        "itemEquipmentPart" to itemEquipmentPart,
        "potentialGrade" to potentialGrade,
        "potentialOptions" to potentialOptions,
        "additionalGrade" to additionalGrade,
        "additionalOptions" to additionalOptions,
        "currentStar" to currentStar,
        "targetStar" to targetStar,
        "status" to status,
        "totalCost" to totalCost,
        "blackCubeCost" to blackCubeCost,
        "additionalCubeCost" to additionalCubeCost,
        "starforceCost" to starforceCost,
        "errorMessage" to errorMessage,
    )
}

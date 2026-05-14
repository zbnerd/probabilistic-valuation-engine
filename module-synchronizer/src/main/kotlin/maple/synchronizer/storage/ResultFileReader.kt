package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

@Component
class ResultFileReader(
    @Value("\${synchronizer.store.base-path:../module-external-api/external-api-data}")
    private val basePath: String,
    private val objectMapper: ObjectMapper,
) {
    fun readAndGroupByCompositeKey(objectKey: String): List<GroupedEquipmentResult> {
        val path = Paths.get(basePath, objectKey)
        if (!Files.exists(path)) {
            throw IllegalStateException("Result file not found: $path")
        }

        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            val items = reader.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { parseItem(it) }
                .toList()

            return items.groupBy { "${it.ocid}:${it.presetNo}" }
                .map { (readKey, group) ->
                    GroupedEquipmentResult(
                        readKey = readKey,
                        ocid = group.first().ocid,
                        presetNo = group.first().presetNo,
                        items = group,
                    )
                }
        }
    }

    fun parseItem(line: String): CalculatedEquipmentItem? {
        return runCatching {
            val node = objectMapper.readTree(line)
            val ocid = node.get("ocid")?.asText() ?: return null
            val presetNo = node.get("presetNo")?.asInt() ?: return null
            CalculatedEquipmentItem(
                ocid = ocid,
                presetNo = presetNo,
                itemName = node.get("itemName")?.asText() ?: "",
                itemLevel = node.get("itemLevel")?.asInt() ?: 0,
                itemPart = node.get("itemPart")?.asText() ?: "",
                itemEquipmentPart = node.get("itemEquipmentPart")?.asText(),
                potentialGrade = node.get("potentialGrade")?.asText(),
                potentialOptions = node.get("potentialOptions")?.map { it.asText() },
                additionalGrade = node.get("additionalGrade")?.asText(),
                additionalOptions = node.get("additionalOptions")?.map { it.asText() },
                currentStar = node.get("currentStar")?.asInt() ?: 0,
                targetStar = node.get("targetStar")?.asInt() ?: 0,
                status = node.get("status")?.asText() ?: "UNKNOWN",
                totalCost = node.get("totalCost")?.decimalValue() ?: BigDecimal.ZERO,
                blackCubeCost = node.get("blackCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
                additionalCubeCost = node.get("additionalCubeCost")?.decimalValue() ?: BigDecimal.ZERO,
                starforceCost = node.get("starforceCost")?.decimalValue() ?: BigDecimal.ZERO,
                errorMessage = node.get("errorMessage")?.asText(),
            )
        }.getOrNull()
    }
}

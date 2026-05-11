package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

@Component
class ResultFileReader(
    @Value("\${synchronizer.store.base-path:../module-external-api/external-api-data}")
    private val basePath: String,
    private val objectMapper: ObjectMapper,
) {
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val ioDispatcher = vtExecutor.asCoroutineDispatcher()

    fun readAndGroupByCompositeKey(objectKey: String): List<GroupedEquipmentResult> {
        val path = Paths.get(basePath, objectKey)
        if (!Files.exists(path)) {
            throw IllegalStateException("Result file not found: $path")
        }

        return runBlocking {
            val lines = withContext(ioDispatcher) {
                GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
                    reader.lineSequence().filter { it.isNotBlank() }.toList()
                }
            }

            withContext(Dispatchers.Default) {
                val parsed = lines.map { line ->
                    async { parseItem(line) }
                }.awaitAll().filterNotNull()

                parsed.groupBy { "${it.ocid}:${it.presetNo}" }
                    .map { (readKey, items) ->
                        GroupedEquipmentResult(
                            readKey = readKey,
                            ocid = items.first().ocid,
                            presetNo = items.first().presetNo,
                            items = items,
                        )
                    }
            }
        }
    }

    private fun parseItem(line: String): CalculatedEquipmentItem? {
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

    @PreDestroy
    fun close() {
        vtExecutor.close()
    }
}

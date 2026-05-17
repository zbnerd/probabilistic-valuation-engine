package maple.externalapi.reader

import java.nio.file.Files
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class UserIgnCsvReader(
    @Value("\${external-api.csv.path:module-app/src/main/resources/data/userIgn_List.csv}")
    private val csvPath: String,
) {
    private val log = LoggerFactory.getLogger(UserIgnCsvReader::class.java)

    fun readAll(): List<String> {
        val path = Paths.get(csvPath)
        if (!Files.exists(path)) {
            log.warn("[CsvReader] file not found: {}", path.toAbsolutePath())
            return emptyList()
        }
        val igns = Files.readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        log.info("[CsvReader] loaded {} IGNs from {}", igns.size, csvPath)
        return igns
    }

    fun readBatch(offset: Int, limit: Int): List<String> {
        val all = readAll()
        if (offset >= all.size) return emptyList()
        val end = minOf(offset + limit, all.size)
        return all.subList(offset, end)
    }
}

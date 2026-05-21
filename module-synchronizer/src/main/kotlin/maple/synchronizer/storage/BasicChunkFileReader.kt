package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class BasicRecord(
    val userIgn: String,
    val ocid: String,
    val worldName: String?,
    val characterClass: String?,
    val characterLevel: Int?,
    val guildName: String?,
    val compressedBody: ByteArray,
    val bodyHash: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Component
class BasicChunkFileReader(
    @Value("\${synchronizer.store.base-path:../data}")
    private val basePath: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(objectKey: String): List<BasicRecord> {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            val records = mutableListOf<BasicRecord>()
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    parseRecord(line)?.let { records.add(it) }
                }
                line = reader.readLine()
            }
            log.info("[BasicChunkFileReader] parsed {} records from {}", records.size, objectKey)
            return records
        }
    }

    private fun parseRecord(line: String): BasicRecord? {
        return runCatching {
            val node = objectMapper.readTree(line)
            if (node.get("status")?.asText() != "SUCCESS") return null
            if (node.get("endpoint")?.asText() != "character-basic") return null

            val ocid = node.get("key")?.asText() ?: return null
            val body = node.get("body") ?: return null

            val userIgn = body.get("character_name")?.asText() ?: return null
            val worldName = body.get("world_name")?.asText()
            val characterClass = body.get("character_class")?.asText()
            val characterLevel = body.get("character_level")?.asInt()
            val guildName = body.get("guild_name")?.asText()

            val bodyBytes = objectMapper.writeValueAsBytes(body)
            val compressed = GzipUtils.compress(bodyBytes)
            val hash = sha256Hex(bodyBytes)

            BasicRecord(
                userIgn = userIgn,
                ocid = ocid,
                worldName = worldName,
                characterClass = characterClass,
                characterLevel = characterLevel,
                guildName = guildName,
                compressedBody = compressed,
                bodyHash = hash,
            )
        }.getOrNull()
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

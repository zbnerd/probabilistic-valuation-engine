package maple.expectation.infrastructure.alert.factory

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.alert.message.AlertMessage
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.HttpHeaders

/**
 * Discord Message Factory
 *
 * <p>Converts AlertMessage to Discord webhook payload format
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
object MessageFactory {

    private val log = LoggerFactory.getLogger(MessageFactory::class.java)
    private val objectMapper = ObjectMapper()
    private val COLOR_ERROR = 0xFF0000 // Red
    private val COLOR_INFO = 0x00FF00 // Green

    /** Convert AlertMessage to Discord JSON payload */
    fun toDiscordPayload(message: AlertMessage): String {
        return try {
            val payload = buildDiscordPayload(message)
            objectMapper.writeValueAsString(payload)
        } catch (e: JsonProcessingException) {
            log.error("[MessageFactory] Failed to serialize Discord payload: {}", e.message, e)
            buildFallbackPayload(message)
        }
    }

    /** Build Discord payload object from AlertMessage */
    private fun buildDiscordPayload(message: AlertMessage): DiscordPayload {
        // Build description
        val description = StringBuilder(message.getMessage())

        // Add error details if present
        if (message.getError() != null) {
            description.append("\n\n**Error:**\n```\n")
            description.append(message.getError().toString())
            description.append("\n```")
        }

        // Build embed
        val embed = Embed(
            message.getTitle(),
            description.toString(),
            if (message.getError() != null) COLOR_ERROR else COLOR_INFO,
            emptyList(),
            Footer("MapleExpectation Alert System"),
            java.time.Instant.now().toString()
        )

        // Discord API requires either content OR embeds (not both)
        // When using embeds, content should be null or empty string
        return DiscordPayload("", listOf(embed))
    }

    /** Fallback payload for serialization failure */
    private fun buildFallbackPayload(message: AlertMessage): String {
        return String.format(
            "{\"content\":\"**%s**\\n%s\"}",
            escapeJson(message.getTitle()),
            escapeJson(message.getMessage())
        )
    }

    /** Escape special JSON characters */
    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /** Create HTTP headers for Discord webhook */
    fun createDiscordHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return headers
    }

    /** Discord Webhook API payload structure */
    private data class DiscordPayload(
        @JsonProperty("content") val content: String,
        @JsonProperty("embeds") val embeds: List<Embed>
    )

    /** Discord Embed structure */
    private data class Embed(
        val title: String,
        val description: String,
        val color: Int,
        val fields: List<Field>,
        val footer: Footer,
        val timestamp: String
    )

    /** Discord Field structure */
    private data class Field(val name: String, val value: String, val inline: Boolean)

    /** Discord Footer structure */
    private data class Footer(val text: String, val iconUrl: String? = null)
}

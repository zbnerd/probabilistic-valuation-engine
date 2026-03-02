package maple.expectation.infrastructure.notification.discord.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Discord Webhook API DTO
 *
 * Discord Webhook API가 요구하는 JSON 형식:
 * ```json
 * {
 *   "content": "메시지 내용 (optional)",
 *   "embeds": [
 *     {
 *       "title": "제목",
 *       "description": "설명",
 *       "color": 16711680,
 *       "fields": [...],
 *       "footer": {"text": "..."},
 *       "timestamp": "2025-02-12T00:00:00Z"
 *     }
 *   ]
 * }
 * ```
 *
 * @see <a href="https://discord.com/developers/docs/resources/webhook#execute-webhook">Discord Webhook API</a>
 */
data class DiscordMessage(
    @JsonProperty("embeds")
    val embeds: List<Embed>
) {
    data class Embed(
        @JsonProperty("title")
        val title: String,
        @JsonProperty("description")
        val description: String,
        @JsonProperty("color")
        val color: Int,
        @JsonProperty("fields")
        val fields: List<Field>,
        @JsonProperty("footer")
        val footer: Footer,
        @JsonProperty("timestamp")
        val timestamp: String
    )

    data class Field(
        @JsonProperty("name")
        val name: String,
        @JsonProperty("value")
        val value: String,
        @JsonProperty("inline")
        val inline: Boolean
    )

    data class Footer(
        @JsonProperty("text")
        val text: String
    )
}

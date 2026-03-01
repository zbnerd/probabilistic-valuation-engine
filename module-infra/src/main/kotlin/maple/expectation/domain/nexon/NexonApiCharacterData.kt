package maple.expectation.domain.nexon

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Nexon API character data response.
 *
 * This is a simplified version of the actual Nexon API response. In production, this should
 * match the actual Nexon Open API schema.
 *
 * **Anti-Corruption Layer:** This DTO isolates the external API structure from
 * internal domain models. Changes in Nexon API should not propagate to internal business logic.
 *
 * @see [Nexon Open API Documentation](https://openapi.nexon.com/maplestory)
 */
@Entity
@Table(name = "nexon_character_data")
data class NexonApiCharacterData(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @JsonProperty("ocid")
    val ocid: String? = null,

    @JsonProperty("character_name")
    val characterName: String? = null,

    @JsonProperty("world_name")
    val worldName: String? = null,

    @JsonProperty("character_class")
    val characterClass: String? = null,

    @JsonProperty("character_level")
    val characterLevel: Int? = null,

    @JsonProperty("character_guild_name")
    val guildName: String? = null,

    @JsonProperty("character_image")
    val characterImageUrl: String? = null,

    @JsonProperty("date")
    val date: Instant? = null
) {
    // Additional fields can be added as needed
    // This is a minimal subset for demonstration
}

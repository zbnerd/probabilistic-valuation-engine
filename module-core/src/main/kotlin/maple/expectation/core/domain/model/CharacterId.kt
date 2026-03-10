package maple.expectation.core.domain.model

/**
 * Character ID domain model.
 *
 * Represents a unique character identifier.
 *
 * Pure domain model - no external dependencies.
 *
 * @property value the OCID (unique character identifier)
 */
data class CharacterId(@get:JvmName("value") val value: String) {
    init {
        require(value.isNotBlank()) { "Character ID cannot be null or blank" }
    }

    /**
     * Check if the character ID is valid.
     *
     * @return true if valid, false otherwise
     */
    fun isValid(): Boolean = value.isNotBlank()

    companion object {
        /**
         * Create a character ID.
         */
        @JvmStatic
        fun of(ocid: String): CharacterId = CharacterId(ocid)
    }
}

package maple.expectation.core.domain.model

import java.time.LocalDateTime

/**
 * Item price domain model.
 *
 * Represents the market price of an item from Nexon's API.
 *
 * Pure domain model - no external dependencies.
 *
 * @property itemId the unique item identifier
 * @property itemName the name of the item
 * @property price the current market price
 * @property updatedAt the timestamp when the price was last updated
 */
data class ItemPrice(
    val itemId: Long,
    val itemName: String,
    val price: Long,
    val updatedAt: LocalDateTime
) {
    init {
        require(itemId > 0) { "itemId must be positive" }
        require(itemName.isNotBlank()) { "itemName cannot be null or blank" }
        require(price >= 0) { "price cannot be negative" }
    }

    /**
     * Check if the price data is fresh (within specified duration).
     *
     * @param hours the maximum age in hours
     * @return true if price data is fresh, false otherwise
     */
    fun isFreshWithinHours(hours: Long): Boolean {
        return updatedAt.plusHours(hours).isAfter(LocalDateTime.now())
    }

    companion object {
        /**
         * Create an item price with current timestamp.
         */
        @JvmStatic
        fun of(itemId: Long, itemName: String, price: Long): ItemPrice {
            return ItemPrice(itemId, itemName, price, LocalDateTime.now())
        }
    }
}

package maple.expectation.infrastructure.cache

import java.time.Duration

/**
 * Centralized cache type definitions.
 *
 * <p>Supports OCP (Open/Closed Principle) by eliminating scattered hard-coded cache names. New
 * cache types can be added here without modifying client code.
 */
enum class CacheType(
    /** Cache name */
    val name: String,

    /** Time-to-live duration */
    val ttl: Duration
) {
    /** Equipment data cache (5 min TTL) */
    EQUIPMENT("equipment", Duration.ofMinutes(5)),

    /** OCID mapping cache (30 min TTL) */
    OCID("ocidCache", Duration.ofMinutes(30)),

    /** Total expectation cache (5 min TTL) */
    TOTAL_EXPECTATION("totalExpectation", Duration.ofMinutes(5)),

    /** Character basic info cache (15 min TTL) */
    CHARACTER_BASIC("characterBasic", Duration.ofMinutes(15)),

    /** OCID negative cache (30 min TTL) */
    OCID_NEGATIVE("ocidNegativeCache", Duration.ofMinutes(30)),

    /** Like count cache (5 min TTL) */
    LIKE_COUNT("likeCount", Duration.ofMinutes(5));

    override fun toString(): String = name
}

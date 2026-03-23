package maple.expectation.infrastructure.cache.tiered

import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Type-safe wrapper for cached values that preserves concrete type information
 *
 * <h3>Purpose</h3>
 *
 * <p>Solves ClassCastException when deserializing from PostgreSQL L2 cache.
 * Without type information, Jackson defaults to LinkedHashMap for JSON objects.
 *
 * <h3>How It Works</h3>
 *
 * <ul>
 *   <li>Stores the actual value</li>
 *   <li>Includes type information via Jackson's @JsonTypeInfo</li>
 *   <li>Enables proper deserialization to concrete types</li>
 * </ul>
 *
 * @see PostgresL2CacheStrategy
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type",
)
data class TypedValue(
    val value: Any?,
)

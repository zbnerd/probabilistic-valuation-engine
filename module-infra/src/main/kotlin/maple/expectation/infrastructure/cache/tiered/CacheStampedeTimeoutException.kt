package maple.expectation.infrastructure.cache.tiered

/**
 * Cache Stampede Timeout Exception (#647)
 *
 * <h3>Purpose</h3>
 * <p>Thrown when a follower thread times out waiting for the leader to populate the cache.
 * Prevents stampede by ensuring followers do NOT call the valueLoader directly.
 *
 * <h3>Handling</h3>
 * <ul>
 *   <li>Pattern 1: {@code @Retryable(value = [CacheStampedeTimeoutException::class])}</li>
 *   <li>Pattern 2: {@code @Recover} fallback method</li>
 *   <li>Pattern 3: {@code executeOrDefault()} wrapping at call site</li>
 * </ul>
 */
class CacheStampedeTimeoutException(
    cacheName: String,
    key: String,
) : RuntimeException("Cache stampede timeout: follower could not retrieve value within wait time. cacheName=$cacheName, key=$key")

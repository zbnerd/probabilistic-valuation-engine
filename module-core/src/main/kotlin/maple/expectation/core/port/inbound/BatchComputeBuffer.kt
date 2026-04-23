package maple.expectation.core.port.inbound

/**
 * Batch Compute Buffer Port (Issue #743)
 *
 * <p>Provides a per-batch memoization buffer for cube probability computations.
 * Implementations store computed results keyed by [maple.expectation.core.dto.cube.CubeComputeKey]
 * to avoid redundant calculations within a single batch request.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Populated during batch computation via getOrCompute</li>
 *   <li>Cleared between batches via [clear]</li>
 * </ul>
 *
 * <h3>Implementation</h3>
 * <ul>
 *   <li>CubeComputeBuffer in module-app uses ConcurrentHashMap for thread-safe access</li>
 * </ul>
 */
interface BatchComputeBuffer {
    fun clear()
    fun stats(): BufferStats

    data class BufferStats(val hits: Int, val misses: Int, val size: Int) {
        val total: Int get() = hits + misses
        val dedupPercent: Double get() = if (total > 0) hits * 100.0 / total else 0.0

        companion object {
            @JvmStatic
            fun of(hits: Int, misses: Int, size: Int) = BufferStats(hits, misses, size)
        }
    }
}

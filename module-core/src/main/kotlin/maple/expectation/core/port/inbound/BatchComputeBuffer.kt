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
}

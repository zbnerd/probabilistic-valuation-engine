package maple.expectation.core.port.out

/**
 * Port for querying buffer status information.
 *
 * <p>This interface provides read-only access to buffer state metrics, primarily used for
 * monitoring and health check purposes.
 *
 * <h3>Usage</h3>
 *
 * <p>Implemented by module-infra adapters to provide buffer statistics from the underlying
 * data store (Redis, database, etc.).
 *
 * @see maple.expectation.core.port.in.BufferStatusCommand
 */
interface BufferStatusQuery {

    /**
     * Gets the total count of pending items across all buffers.
     *
     * <p>This metric represents the current workload waiting to be processed and is used for:
     * <ul>
     *   <li>Health monitoring and alerting
     *   <li>Load balancing decisions
     *   <li>Performance metrics collection
     * </ul>
     *
     * @return total pending count, or 0 if no buffers are active
     */
    fun getTotalPendingCount(): Long
}

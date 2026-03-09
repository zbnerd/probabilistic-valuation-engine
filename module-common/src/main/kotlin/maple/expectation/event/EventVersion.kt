package maple.expectation.event

/**
 * Event Schema Version Constants
 *
 * <h3>Purpose</h3>
 *
 * Provides centralized version constants for event schema evolution. These versions are used to:
 *
 * <ul>
 *   <li>Tag events with their schema version at creation time
 *   <li>Enable EventUpcaster to transform between versions
 *   <li>Validate consumer compatibility with event schemas
 *   <li>Support backward and forward compatibility
 * </ul>
 *
 * <h3>Versioning Strategy</h3>
 *
 * <p>Schema versions are monotonically increasing integers starting from 1:
 *
 * <ul>
 *   <li>V1 (1): Initial schema version
 *   <li>V2 (2): First breaking change
 *   <li>...
 *   <li>CURRENT: Latest schema version
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * // Create event with current version
 * val event = IntegrationEvent.of(
 *     type = "CharacterCreated",
 *     payload = payload,
 *     version = EventVersion.CURRENT
 * )
 *
 * // Check if upcasting is needed
 * if (event.version < EventVersion.CURRENT) {
 *     val upcasted = eventUpcaster.upcast(event.type, event.version, event.payload)
 * }
 * </pre>
 *
 * <h3>Migration Rules</h3>
 *
 * <p>When introducing a new schema version:
 *
 * <ol>
 *   <li>Add new constant (e.g., V2 = 2)</li>
 *   <li>Update CURRENT to new version</li>
 *   <li>Register upcaster in EventUpcasterRegistry</li>
 *   <li>Update consumers to handle new schema</li>
 * </ol>
 *
 * @see maple.expectation.application.worker.EventUpcaster
 * @see maple.expectation.application.worker.EventUpcasterRegistry
 */
object EventVersion {
    /** Initial schema version (baseline) */
    const val V1 = 1

    /** Current schema version - increment for breaking changes */
    const val CURRENT = V1

    /**
     * Check if a version is supported (not newer than current)
     *
     * @param version Version to check
     * @return true if version is supported, false if it's from the future
     */
    @JvmStatic
    fun isSupported(version: Int): Boolean = version in 1..CURRENT

    /**
     * Check if upcasting is needed
     *
     * @param version Source version
     * @return true if version is older than current
     */
    @JvmStatic
    fun needsUpcasting(version: Int): Boolean = version < CURRENT
}

package maple.expectation.core.port.out

/**
 * Port interface for persistence tracking database operations.
 * Decouples DB access from application layer (Port/Adapter pattern).
 */
interface PersistenceTrackerPort {
    fun insertPending(ocid: String, instanceId: String)
    fun markCompleted(ocid: String)
    fun findPendingOperations(): List<String>
}

package maple.synchronizer.repository

/**
 * Tuning constants for chunk write paths. Centralized so the synchronizer producer/consumer
 * pair agrees on batch sizing — mismatched SUB_BATCH_SIZE between repository callers is a
 * silent data-loss source (Issue: #1093).
 */
internal object ChunkWriteConstants {
    /**
     * Items per sub-batch in bulk upsert calls. 100 fits a single PG prepared-statement
     * parameter limit (32k parameters / ~300 columns/row).
     */
    const val SUB_BATCH_SIZE: Int = 100
}

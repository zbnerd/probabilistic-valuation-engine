package maple.expectation.core.port.out

/**
 * Like Relation Buffer Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <p>Pluggable strategy for buffering like relations. Implementations
 * vary by storage backend (in-memory, distributed cache, etc.) and are
 * selected via configuration.
 */
interface LikeRelationBufferStrategy {

    /** Strategy type enum */
    enum class StrategyType {
        /** In-Memory hybrid (legacy) */
        IN_MEMORY,

        /** Distributed cache (V5 Stateless) */
        DISTRIBUTED,
    }

    /** Get current strategy type */
    fun getType(): StrategyType

    /**
     * Add like relation
     *
     * @param accountId account of user who liked
     * @param targetOcid target character OCID
     * @return true: newly added, false: duplicate, null: storage failure
     */
    fun addRelation(accountId: String, targetOcid: String): Boolean?

    /**
     * Check if like relation exists
     *
     * @param accountId account of user who liked
     * @param targetOcid target character OCID
     * @return true: exists, false: not exists, null: storage failure
     */
    fun exists(accountId: String, targetOcid: String): Boolean?

    /**
     * Remove like relation
     *
     * @param accountId account of user who liked
     * @param targetOcid target character OCID
     * @return true: removed, false: not exists
     */
    fun removeRelation(accountId: String, targetOcid: String): Boolean?

    /**
     * Fetch and remove pending relations for DB sync (atomic)
     *
     * @param limit max fetch count
     * @return pending relation set (accountId:targetOcid format)
     */
    fun fetchAndRemovePending(limit: Int): Set<String>

    /** Build relation key Format: {accountId}:{targetOcid} */
    fun buildRelationKey(accountId: String, targetOcid: String): String

    /**
     * Parse relation key
     *
     * @return [accountId, targetOcid]
     */
    fun parseRelationKey(relationKey: String): Array<String>

    /**
     * Check if relation exists in unliked set (explicit unlike tracking)
     *
     * <p>Tracks explicitly unliked relations to distinguish cold start (storage empty) from actual
     * unlike.
     *
     * @param accountId account of user who liked
     * @param targetOcid target character OCID
     * @return true: explicitly unliked, false: no unlike record, null: storage failure
     */
    fun existsInUnliked(accountId: String, targetOcid: String): Boolean? = null

    /** Get total relation count */
    fun getRelationsSize(): Int

    /** Get pending relation count */
    fun getPendingSize(): Int
}

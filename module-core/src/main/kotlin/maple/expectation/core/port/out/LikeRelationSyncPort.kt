package maple.expectation.core.port.out

/**
 * Like Relation Sync Port - 좋아요 관계 동기화 작업을 위한 인터페이스
 *
 * <p>Method names reference cache tiers (L1/L2) and persistence layer
 * in a technology-neutral way. Adapters determine the actual storage.
 */
interface LikeRelationSyncPort {
    /**
     * L1 → L2 flush
     */
    fun flushLocalToL2()

    /**
     * L2 → persistence sync
     */
    fun syncL2ToPersistence()
}

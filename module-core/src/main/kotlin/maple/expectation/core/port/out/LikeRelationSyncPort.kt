package maple.expectation.core.port.out

/**
 * Like Relation Sync Port - 좋아요 관계 동기화 작업을 위한 인터페이스
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>module-infra/adapter/LikeRelationSyncAdapter - LikeRelationSyncService에 위임
 * </ul>
 */
interface LikeRelationSyncPort {
    /**
     * L1 (Caffeine) → L2 (Redis) Flush
     */
    fun flushLocalToRedis()

    /**
     * L2 (Redis) → L3 (MySQL) Sync
     */
    fun syncRedisToDatabase()
}

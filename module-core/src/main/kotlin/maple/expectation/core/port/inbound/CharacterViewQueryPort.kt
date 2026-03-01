package maple.expectation.core.port.inbound

/**
 * V5 CQRS Query Side Port (ADR-005)
 *
 * <p>책임: MongoDB CharacterValuationView 조회
 *
 * <p>구현체:
 * <ul>
 *   <li>module-app/adapter/in/CharacterViewQueryPortAdapter - CharacterViewQueryService에 위임
 * </ul>
 */
interface CharacterViewQueryPort {

    /**
     * 캐릭터 조회 (userIgn 기준)
     *
     * @param userIgn 캐릭터 IGN
     * @return CharacterValuationView 또는 null
     */
    fun findByUserIgn(userIgn: String): Any?

    /**
     * 캐릭터 삭제 (Cache Invalidation)
     *
     * @param userIgn 캐릭터 IGN
     */
    fun deleteByUserIgn(userIgn: String)
}

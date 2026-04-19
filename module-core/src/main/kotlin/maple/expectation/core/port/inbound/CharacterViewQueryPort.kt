package maple.expectation.core.port.inbound

import java.util.Optional
import maple.expectation.core.domain.model.character.CharacterView

/**
 * V5 CQRS Query Side Port (ADR-005, Issue #639)
 *
 * <p>책임: PostgreSQL CharacterValuationView 조회
 *
 * <p>구현체:
 * <ul>
 *   <li>module-infra/CharacterViewQueryServicePostgres - PostgreSQL 조회
 * </ul>
 */
interface CharacterViewQueryPort {

    /**
     * 캐릭터 조회 (userIgn 기준)
     *
     * @param userIgn 캐릭터 IGN
     * @return CharacterView 또는 empty
     */
    fun findByUserIgn(userIgn: String): Optional<CharacterView>

    /**
     * 캐릭터 삭제 (Cache Invalidation)
     *
     * @param userIgn 캐릭터 IGN
     */
    fun deleteByUserIgn(userIgn: String)
}

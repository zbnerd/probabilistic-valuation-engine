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
     * V5 CQRS Write: 워커 계산 결과를 뷰 테이블에 upsert
     *
     * <p>Two-phase batch path에서 뷰 테이블 동기화용 (TODO #727 해결)
     *
     * @param userIgn 캐릭터 IGN
     * @param messageId PGMQ 메시지 ID
     * @param characterOcid 캐릭터 OCID
     * @param characterClass 캐릭터 직업
     * @param characterLevel 캐릭터 레벨
     * @param totalExpectedCost 총 기대값
     * @param maxPresetNo 최대 프리셋 번호
     * @param presetNo 프리셋 번호
     * @param presetsJson 프리셋 데이터 JSON
     */
    fun upsertFromCalculation(
        userIgn: String,
        messageId: String?,
        characterOcid: String?,
        characterClass: String?,
        characterLevel: Int?,
        totalExpectedCost: Long,
        maxPresetNo: Int,
        presetNo: Int,
        presetsJson: String,
    )
}

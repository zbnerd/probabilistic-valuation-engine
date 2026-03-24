package maple.expectation.infrastructure.persistence.repository

import maple.expectation.domain.v2.EquipmentExpectationSummary
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 장비 기대값 요약 Repository (#240, P1-11)
 *
 * <p><strong>P1-11 Multi-DataSource:</strong> Uses explicit `"transactionManager"` qualifier
 * to prevent ambiguity in multi-datasource environments (MongoDB read replicas).
 *
 * <h3>성능 최적화 (2026-03-23)</h3>
 *
 * <ul>
 *   <li>BigDecimal → Double로 변경하여 계산 비용 절감
 *   <li>모든 비용 필드를 Double로 저장
 * </ul>
 *
 * @see EquipmentExpectationSummary 연관 엔티티
 * @see <a href="../../../../../docs/adr/013-multi-datasource-transaction-strategy.md">ADR-013: Multi-DataSource Transaction Strategy</a>
 */
interface EquipmentExpectationSummaryRepository : JpaRepository<EquipmentExpectationSummary, Long> {

    /**
     * 기대값 요약 Upsert (동시성 안전) (#262)
     *
     * <h3>Issue #262: Cache Stampede 해결</h3>
     *
     * <p>MySQL `INSERT ... ON DUPLICATE KEY UPDATE`로 동시 쓰기 Race Condition 제거
     *
     * <p>Unique Key: (game_character_id, preset_no)
     *
     * <h3>P1-11 Transaction Management</h3>
     *
     * <p>Uses explicit `"transactionManager"` qualifier with `REQUIRES_NEW` propagation
     * to ensure independent transaction in multi-datasource environments.
     *
     * @param gameCharacterId 캐릭터 ID
     * @param presetNo 프리셋 번호
     * @param totalExpectedCost 총 기대 비용
     * @param blackCubeCost 블랙큐브 비용
     * @param redCubeCost 레드큐브 비용
     * @param additionalCubeCost 에디셔널큐브 비용
     * @param starforceCost 스타포스 비용
     */
    /**
     * 기대값 요약 Upsert (동시성 안전) (#262)
     *
     * <p>H2 호환성: native query 제거, JPA merge 사용
     *
     * @param summary 엔데이트할 요약 엔티티 (기존 레코드 있으면 덮어쓰기)
     * @return 저장된 엔티티
     */
    @Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
    fun saveOrUpdate(summary: EquipmentExpectationSummary): EquipmentExpectationSummary
}

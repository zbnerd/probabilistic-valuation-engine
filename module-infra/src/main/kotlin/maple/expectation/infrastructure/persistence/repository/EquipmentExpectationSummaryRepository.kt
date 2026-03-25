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
}

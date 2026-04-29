package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * V5 CQRS: PostgreSQL Repository for Character Valuation Views
 *
 * MongoDB CharacterValuationRepository의 PostgreSQL 마이그레이션 (Issue #590)
 */
@Repository
interface CharacterValuationViewJpaRepository : JpaRepository<CharacterValuationViewEntity, Long> {

    /**
     * userIgn으로 조회 (O(1) indexed lookup)
     */
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity?

    fun findTopByUserIgnOrderByCalculatedAtDescIdDesc(userIgn: String): CharacterValuationViewEntity?

    /**
     * messageId으로 조회
     */
    fun findByMessageId(messageId: String): CharacterValuationViewEntity?
}

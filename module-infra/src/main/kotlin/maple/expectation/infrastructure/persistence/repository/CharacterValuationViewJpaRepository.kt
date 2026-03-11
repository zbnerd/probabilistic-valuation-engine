package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CharacterValuationViewEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * V5 CQRS: PostgreSQL Repository for Character Valuation Views
 *
 * MongoDB CharacterValuationRepository의 PostgreSQL 마이그레이션
 *
 * @see maple.expectation.infrastructure.mongodb.CharacterValuationRepository
 */
@Repository
interface CharacterValuationViewJpaRepository : JpaRepository<CharacterValuationViewEntity, Long> {

    /**
     * userIgn으로 조회 (O(1) indexed lookup)
     */
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity?

    /**
     * messageId으로 조회
     */
    fun findByMessageId(messageId: String): CharacterValuationViewEntity?

    /**
     * userIgn으로 삭제
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM CharacterValuationViewEntity e WHERE e.userIgn = :userIgn")
    fun deleteByUserIgn(@Param("userIgn") userIgn: String): Int
}

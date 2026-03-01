package maple.expectation.infrastructure.persistence.jpa

import maple.expectation.domain.v2.Member
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Spring Data JPA Repository for Member.
 *
 * <p>This is an INTERNAL repository interface used only by infrastructure layer. Domain layer uses
 * [maple.expectation.domain.repository.MemberRepository] instead.
 *
 * @see maple.expectation.infrastructure.persistence.repository.MemberRepositoryImpl
 */
interface MemberJpaRepository : JpaRepository<Member, Long> {

    /**
     * Find member by UUID.
     *
     * @param uuid the member's UUID
     * @return Member if found, empty otherwise
     */
    fun findByUuid(uuid: String?): Member?

    /**
     * Check if member exists by UUID.
     *
     * @param uuid the UUID to check
     * @return true if exists
     */
    fun existsByUuid(uuid: String?): Boolean

    /**
     * [Guest 포인트 차감] 핵심: WHERE 조건에 'm.point >= :amount'를 추가하여 잔액이 부족하면 업데이트가 아예 실행되지 않도록(반환값 0) 막습니다.
     * -> 락 없이도 정합성 보장!
     */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Member m SET m.point = m.point - :amount
        WHERE m.uuid = :uuid AND m.point >= :amount
        """
    )
    fun decreasePoint(@Param("uuid") uuid: String?, @Param("amount") amount: Long?): Int

    /** [Developer 포인트 증가] 단순 증가이므로 조건 없이 더해줍니다. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Member m SET m.point = m.point + :amount WHERE m.id = :id")
    fun increasePoint(@Param("id") id: Long?, @Param("amount") amount: Long?): Int

    /**
     * [Admin 포인트 증가 - UUID 기반] Admin에게 커피(후원)를 보낼 때 사용합니다.
     *
     * @param uuid Admin의 fingerprint (Member.uuid로 사용)
     * @param amount 증가할 포인트
     * @return 영향받은 행 수 (0이면 해당 Admin Member가 없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Member m SET m.point = m.point + :amount WHERE m.uuid = :uuid")
    fun increasePointByUuid(@Param("uuid") uuid: String?, @Param("amount") amount: Long?): Int

    /**
     * Delete member by UUID.
     *
     * @param uuid the UUID of the member to delete
     */
    fun deleteByUuid(uuid: String?)
}

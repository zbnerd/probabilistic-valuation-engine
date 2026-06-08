package maple.expectation.integration.repository

import maple.expectation.infrastructure.persistence.entity.MemberEntity
import maple.expectation.infrastructure.persistence.repository.MemberRepository
import maple.expectation.test.RepositoryIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * MemberRepository 통합 테스트
 *
 * <p>모든 MemberRepository 메서드의 통합 동작을 검증합니다.
 *
 * <h3>테스트 커버리지</h3>
 * <ul>
 *   <li>findByUuid - UUID로 회원 조회
 *   <li>findById - ID로 회원 조회
 *   <li>save - 신규 회원 생성 및 기존 회원 수정
 *   <li>deleteByUuid - UUID로 회원 삭제
 *   <li>existsByUuid - UUID로 존재 여부 확인
 *   <li>findOrCreateGuest - 기존 회원 반환 또는 신규 게스트 생성
 *   <li>increasePointByUuid - 원자적 포인트 증가
 * </ul>
 *
 * @see MemberRepository
 * @see Member
 */
@Tag("integration")
@Tag("repository")
@DisplayName("MemberRepository 통합 테스트")
class MemberRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("findByUuid: 존재하는 회원을 조회한다")
    fun `findByUuid는 존재하는 회원을 반환한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-001", initialPoint = 1000L)
        val saved = memberRepository.save(member)
        flushAndClear()

        // Act
        val found = memberRepository.findByUuid("test-uuid-001")

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.uuid).isEqualTo("test-uuid-001")
        assertThat(found.point).isEqualTo(1000L)
    }

    @Test
    @DisplayName("findByUuid: 존재하지 않는 UUID로 조회하면 null을 반환한다")
    fun `findByUuid는 존재하지 않는 UUID에 대해 null을 반환한다`() {
        // Act
        val found = memberRepository.findByUuid("non-existent-uuid")

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("findById: 존재하는 ID로 회원을 조회한다")
    fun `findById는 존재하는 ID로 회원을 반환한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-002", initialPoint = 500L)
        val saved = memberRepository.save(member)
        flushAndClear()
        val savedId = saved.id

        // Act
        val found = memberRepository.findById(savedId)

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(savedId)
        assertThat(found.uuid).isEqualTo("test-uuid-002")
    }

    @Test
    @DisplayName("findById: 존재하지 않는 ID로 조회하면 null을 반환한다")
    fun `findById는 존재하지 않는 ID에 대해 null을 반환한다`() {
        // Act
        val found = memberRepository.findById(999999L)

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("findById: null ID로 조회하면 null을 반환한다")
    fun `findById는 null ID에 대해 null을 반환한다`() {
        // Act
        val found = memberRepository.findById(null)

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("save: 신규 회원을 생성한다")
    fun `save는 신규 회원을 생성한다`() {
        // Arrange
        val newMember = createTestMember(uuid = "test-uuid-003", initialPoint = 2000L)

        // Act
        val saved = memberRepository.save(newMember)
        flushAndClear()

        // Assert
        assertThat(saved.id).isNotNull()
        assertThat(saved.uuid).isEqualTo("test-uuid-003")
        assertThat(saved.point).isEqualTo(2000L)
        assertThat(saved.version).isNotNull()

        // DB에서 실제로 조회되는지 확인
        val found = memberRepository.findByUuid("test-uuid-003")
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(saved.id)
    }

    @Test
    @DisplayName("save: 기존 회원을 수정한다")
    fun `save는 기존 회원을 수정한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-004", initialPoint = 1000L)
        val saved = memberRepository.save(member)
        flushAndClear()

        // Act
        val found = memberRepository.findByUuid("test-uuid-004")
        val originalVersion = found!!.version
        found.point = 1500L
        val updated = memberRepository.save(found)
        flushAndClear()

        // Assert
        assertThat(updated.point).isEqualTo(1500L)
        assertThat(updated.version).isNotEqualTo(originalVersion)

        // DB에서 실제로 업데이트되었는지 확인
        val reloaded = memberRepository.findByUuid("test-uuid-004")
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.point).isEqualTo(1500L)
    }

    @Test
    @DisplayName("deleteByUuid: UUID로 회원을 삭제한다")
    fun `deleteByUuid는 회원을 삭제한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-005", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // Act
        memberRepository.deleteByUuid("test-uuid-005")
        flushAndClear()

        // Assert
        val found = memberRepository.findByUuid("test-uuid-005")
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("existsByUuid: 존재하는 UUID에 대해 true를 반환한다")
    fun `existsByUuid는 존재하는 UUID에 대해 true를 반환한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-006", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // Act
        val exists = memberRepository.existsByUuid("test-uuid-006")

        // Assert
        assertThat(exists).isTrue
    }

    @Test
    @DisplayName("existsByUuid: 존재하지 않는 UUID에 대해 false를 반환한다")
    fun `existsByUuid는 존재하지 않는 UUID에 대해 false를 반환한다`() {
        // Act
        val exists = memberRepository.existsByUuid("non-existent-uuid")

        // Assert
        assertThat(exists).isFalse
    }

    @Test
    @DisplayName("findOrCreateGuest: 존재하지 않는 UUID로 새 게스트를 생성한다")
    fun `findOrCreateGuest는 존재하지 않는 UUID로 새 게스트를 생성한다`() {
        // Act
        val guest = memberRepository.findOrCreateGuest("new-guest-uuid", 500L)
        flushAndClear()

        // Assert
        assertThat(guest.uuid).isEqualTo("new-guest-uuid")
        assertThat(guest.point).isEqualTo(500L)
        assertThat(guest.id).isNotNull()

        // DB에서 실제로 생성되었는지 확인
        val found = memberRepository.findByUuid("new-guest-uuid")
        assertThat(found).isNotNull
        assertThat(found!!.point).isEqualTo(500L)
    }

    @Test
    @DisplayName("findOrCreateGuest: 존재하는 UUID로 기존 회원을 반환한다")
    fun `findOrCreateGuest는 존재하는 UUID로 기존 회원을 반환한다`() {
        // Arrange
        val existingMember = createTestMember(uuid = "existing-guest-uuid", initialPoint = 2000L)
        memberRepository.save(existingMember)
        flushAndClear()

        // Act
        val guest = memberRepository.findOrCreateGuest("existing-guest-uuid", 500L)

        // Assert
        assertThat(guest.uuid).isEqualTo("existing-guest-uuid")
        assertThat(guest.point).isEqualTo(2000L) // 기존 포인트 유지
        assertThat(guest.id).isEqualTo(existingMember.id)
    }

    @Test
    @DisplayName("increasePointByUuid: 회원의 포인트를 증가시킨다")
    fun `increasePointByUuid는 회원의 포인트를 증가시킨다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-007", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // Act
        val updatedRows = memberRepository.increasePointByUuid("test-uuid-007", 500L)
        flushAndClear()

        // Assert
        assertThat(updatedRows).isEqualTo(1)

        val found = memberRepository.findByUuid("test-uuid-007")
        assertThat(found).isNotNull
        assertThat(found!!.point).isEqualTo(1500L) // 1000 + 500
    }

    @Test
    @DisplayName("increasePointByUuid: 존재하지 않는 UUID에 대해 0을 반환한다")
    fun `increasePointByUuid는 존재하지 않는 UUID에 대해 0을 반환한다`() {
        // Act
        val updatedRows = memberRepository.increasePointByUuid("non-existent-uuid", 500L)

        // Assert
        assertThat(updatedRows).isEqualTo(0)
    }

    @Test
    @DisplayName("increasePointByUuid: 여러 증가가 누적된다")
    fun `increasePointByUuid는 여러 증가가 누적된다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-008", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // Act
        memberRepository.increasePointByUuid("test-uuid-008", 300L)
        memberRepository.increasePointByUuid("test-uuid-008", 200L)
        memberRepository.increasePointByUuid("test-uuid-008", 500L)
        flushAndClear()

        // Assert
        val found = memberRepository.findByUuid("test-uuid-008")
        assertThat(found).isNotNull
        assertThat(found!!.point).isEqualTo(2000L) // 1000 + 300 + 200 + 500
    }

    // ==================== Helper Methods ====================

    /**
     * 테스트용 Member 엔티티 생성
     *
     * <p>Member의 private constructor에 접근하기 위해 리플렉션 사용
     */
    private fun createTestMember(uuid: String, initialPoint: Long): MemberEntity {
        val constructor = MemberEntity::class.java.getDeclaredConstructor(String::class.java, Long::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(uuid, initialPoint)
    }
}

package maple.expectation.integration.repository

import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository
import maple.expectation.test.RepositoryIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * GameCharacterRepository 통합 테스트
 *
 * <p>모든 GameCharacterRepository 메서드의 통합 동작을 검증합니다.
 *
 * <h3>테스트 커버리지</h3>
 * <ul>
 *   <li>findByOcid - OCID로 캐릭터 조회
 *   <li>findByUserIgn - 유저 IGN으로 캐릭터 조회
 *   <li>save - 신규 캐릭터 생성 및 기존 캐릭터 수정
 *   <li>deleteByOcid - OCID로 캐릭터 삭제
 *   <li>existsByOcid - OCID로 존재 여부 확인
 *   <li>findAll - 전체 조회 및 페이지네이션
 *   <li>incrementLikeCount - 좋아요 수 증가
 * </ul>
 *
 * @see GameCharacterRepository
 * @see GameCharacter
 */
@Tag("integration")
@Tag("repository")
@DisplayName("GameCharacterRepository 통합 테스트")
class GameCharacterRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    private lateinit var gameCharacterRepository: GameCharacterRepository

    @Test
    @DisplayName("findByOcid: 존재하는 OCID로 캐릭터를 조회한다")
    fun `findByOcid는 존재하는 OCID로 캐릭터를 반환한다`() {
        // Arrange
        val ocid = "test-ocid-001"
        val character = createTestCharacter(ocid = ocid, userIgn = "test-user-001")
        val saved = gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        val found = gameCharacterRepository.findByOcid(ocid)

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.getOcid()).isEqualTo(ocid)
        assertThat(found.userIgn.value).isEqualTo("test-user-001")
        assertThat(found.likeCount).isEqualTo(0L)
    }

    @Test
    @DisplayName("findByOcid: 존재하지 않는 OCID로 조회하면 null을 반환한다")
    fun `findByOcid는 존재하지 않는 OCID에 대해 null을 반환한다`() {
        // Act
        val found = gameCharacterRepository.findByOcid("non-existent-ocid")

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("findByUserIgn: 존재하는 유저 IGN으로 캐릭터를 조회한다")
    fun `findByUserIgn는 존재하는 유저 IGN으로 캐릭터를 반환한다`() {
        // Arrange
        val userIgn = "test-user-002"
        val character = createTestCharacter(ocid = "test-ocid-002", userIgn = userIgn)
        val saved = gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        val found = gameCharacterRepository.findByUserIgn(userIgn)

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.userIgn.value).isEqualTo(userIgn)
        assertThat(found.getOcid()).isEqualTo("test-ocid-002")
    }

    @Test
    @DisplayName("findByUserIgn: 존재하지 않는 유저 IGN으로 조회하면 null을 반환한다")
    fun `findByUserIgn는 존재하지 않는 유저 IGN에 대해 null을 반환한다`() {
        // Act
        val found = gameCharacterRepository.findByUserIgn("non-existent-user")

        // Assert
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("save: 신규 캐릭터를 생성한다")
    fun `save는 신규 캐릭터를 생성한다`() {
        // Arrange
        val newCharacter = createTestCharacter(ocid = "test-ocid-003", userIgn = "test-user-003")

        // Act
        val saved = gameCharacterRepository.save(newCharacter)
        flushAndClear()

        // Assert
        assertThat(saved.id).isNotNull()
        assertThat(saved.getOcid()).isEqualTo("test-ocid-003")
        assertThat(saved.userIgn.value).isEqualTo("test-user-003")
        assertThat(saved.likeCount).isEqualTo(0L)
        assertThat(saved.version).isNotNull()

        // DB에서 실제로 조회되는지 확인
        val found = gameCharacterRepository.findByOcid("test-ocid-003")
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(saved.id)
    }

    @Test
    @DisplayName("save: 기존 캐릭터를 수정한다")
    fun `save는 기존 캐릭터를 수정한다`() {
        // Arrange
        val character = createTestCharacter(ocid = "test-ocid-004", userIgn = "test-user-004")
        val saved = gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        val found = gameCharacterRepository.findByOcid("test-ocid-004")
        val originalVersion = found!!.version
        val updated = found.withBasicInfo(
            worldName = "스카니아",
            characterClass = "전사",
            characterImage = "https://example.com/image.png",
        )
        val savedAgain = gameCharacterRepository.save(updated)
        flushAndClear()

        // Assert - savedAgain의 데이터 검증
        assertThat(savedAgain.worldName).isEqualTo("스카니아")
        assertThat(savedAgain.characterClass).isEqualTo("전사")
        assertThat(savedAgain.characterImage).isEqualTo("https://example.com/image.png")
        assertThat(savedAgain.basicInfoUpdatedAt).isNotNull()

        // DB에서 실제로 업데이트되었는지 확인
        val reloaded = gameCharacterRepository.findByOcid("test-ocid-004")
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.worldName).isEqualTo("스카니아")
        assertThat(reloaded.characterClass).isEqualTo("전사")
        // Version은 flush 후 DB에서 증가하므로 reloaded에서 검증
        assertThat(reloaded.version).isNotEqualTo(originalVersion)
    }

    @Test
    @DisplayName("deleteByOcid: OCID로 캐릭터를 삭제한다")
    fun `deleteByOcid는 캐릭터를 삭제한다`() {
        // Arrange
        val character = createTestCharacter(ocid = "test-ocid-005", userIgn = "test-user-005")
        gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        gameCharacterRepository.deleteByOcid("test-ocid-005")
        flushAndClear()

        // Assert
        val found = gameCharacterRepository.findByOcid("test-ocid-005")
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("existsByOcid: 존재하는 OCID에 대해 true를 반환한다")
    fun `existsByOcid는 존재하는 OCID에 대해 true를 반환한다`() {
        // Arrange
        val character = createTestCharacter(ocid = "test-ocid-006", userIgn = "test-user-006")
        gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        val exists = gameCharacterRepository.existsByOcid("test-ocid-006")

        // Assert
        assertThat(exists).isTrue
    }

    @Test
    @DisplayName("existsByOcid: 존재하지 않는 OCID에 대해 false를 반환한다")
    fun `existsByOcid는 존재하지 않는 OCID에 대해 false를 반환한다`() {
        // Act
        val exists = gameCharacterRepository.existsByOcid("non-existent-ocid")

        // Assert
        assertThat(exists).isFalse
    }

    @Test
    @DisplayName("findAll: 전체 캐릭터를 조회한다")
    fun `findAll은 전체 캐릭터를 반환한다`() {
        // Arrange
        val character1 = createTestCharacter(ocid = "test-ocid-007", userIgn = "test-user-007")
        val character2 = createTestCharacter(ocid = "test-ocid-008", userIgn = "test-user-008")
        val character3 = createTestCharacter(ocid = "test-ocid-009", userIgn = "test-user-009")
        gameCharacterRepository.save(character1)
        gameCharacterRepository.save(character2)
        gameCharacterRepository.save(character3)
        flushAndClear()

        // Act
        val allCharacters = gameCharacterRepository.findAll()

        // Assert
        assertThat(allCharacters).hasSizeGreaterThanOrEqualTo(3)
        assertThat(allCharacters.map { it.getOcid() })
            .contains("test-ocid-007", "test-ocid-008", "test-ocid-009")
    }

    @Test
    @DisplayName("findAll: 페이지네이션으로 캐릭터를 조회한다")
    fun `findAll은 페이지네이션으로 캐릭터를 반환한다`() {
        // Arrange
        val ocids = (1..15).map { "test-ocid-page-$it" }
        ocids.forEach { ocid ->
            val character = createTestCharacter(ocid = ocid, userIgn = "test-user-$ocid")
            gameCharacterRepository.save(character)
        }
        flushAndClear()

        // Act - 첫 번째 페이지
        val firstPage = gameCharacterRepository.findAll(PageRequest.of(0, 5))

        // Assert
        assertThat(firstPage.content).hasSize(5)
        assertThat(firstPage.totalElements).isGreaterThanOrEqualTo(15)
        assertThat(firstPage.totalPages).isGreaterThanOrEqualTo(3)
        assertThat(firstPage.isFirst).isTrue

        // Act - 두 번째 페이지
        val secondPage = gameCharacterRepository.findAll(PageRequest.of(1, 5))

        // Assert
        assertThat(secondPage.content).hasSize(5)
        assertThat(secondPage.isFirst).isFalse
        assertThat(secondPage.hasNext).isTrue
    }

    @Test
    @DisplayName("incrementLikeCount: 좋아요 수를 증가시킨다")
    fun `incrementLikeCount는 좋아요 수를 증가시킨다`() {
        // Arrange
        val character = createTestCharacter(ocid = "test-ocid-010", userIgn = "test-user-010")
        gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        gameCharacterRepository.incrementLikeCount("test-user-010", 5L)
        flushAndClear()

        // Assert
        val found = gameCharacterRepository.findByUserIgn("test-user-010")
        assertThat(found).isNotNull
        assertThat(found!!.likeCount).isEqualTo(5L)
    }

    @Test
    @DisplayName("incrementLikeCount: 여러 번 증가시키면 누적된다")
    fun `incrementLikeCount는 여러 번 증가시키면 누적된다`() {
        // Arrange
        val character = createTestCharacter(ocid = "test-ocid-011", userIgn = "test-user-011")
        gameCharacterRepository.save(character)
        flushAndClear()

        // Act
        gameCharacterRepository.incrementLikeCount("test-user-011", 3L)
        gameCharacterRepository.incrementLikeCount("test-user-011", 7L)
        gameCharacterRepository.incrementLikeCount("test-user-011", 10L)
        flushAndClear()

        // Assert
        val found = gameCharacterRepository.findByUserIgn("test-user-011")
        assertThat(found).isNotNull
        assertThat(found!!.likeCount).isEqualTo(20L) // 3 + 7 + 10
    }

    @Test
    @DisplayName("incrementLikeCount: 존재하지 않는 유저에도 동작한다")
    fun `incrementLikeCount는 존재하지 않는 유저에도 에러 없이 동작한다`() {
        // Act & Assert - 에러가 발생하지 않아야 함
        gameCharacterRepository.incrementLikeCount("non-existent-user", 5L)
        flushAndClear()

        // 존재하지 않으므로 조회되지 않음
        val found = gameCharacterRepository.findByUserIgn("non-existent-user")
        assertThat(found).isNull()
    }

    // ==================== Helper Methods ====================

    /**
     * 테스트용 GameCharacter 생성
     *
     * @param ocid 캐릭터 OCID
     * @param userIgn 유저 IGN
     * @return GameCharacter 도메인 객체
     */
    private fun createTestCharacter(ocid: String, userIgn: String): GameCharacter = GameCharacter.create(
        userIgn = UserIgn(userIgn),
        characterId = CharacterId(ocid),
    )
}

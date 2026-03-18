package maple.expectation.integration.repository

import maple.expectation.core.domain.model.like.CharacterLike
import maple.expectation.domain.repository.CharacterLikeRepository
import maple.expectation.test.RepositoryIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * CharacterLikeRepository 통합 테스트
 *
 * <p>모든 CharacterLikeRepository 메서드의 통합 동작을 검증합니다.
 *
 * <h3>테스트 커버리지</h3>
 * <ul>
 *   <li>findByTargetOcidAndLikerAccountId - 단일 조회
 *   <li>findByLikerAccountId - 사용자별 좋아요 목록
 *   <li>findByTargetOcid - 캐릭터별 좋아요 목록
 *   <li>save - 좋아요 생성
 *   <li>delete - 좋아요 삭제
 *   <li>deleteByTargetOcidAndLikerAccountId - 조건부 삭제
 *   <li>countByTargetOcid - 캐릭터별 카운트
 *   <li>countByLikerAccountId - 사용자별 카운트
 *   <li>existsByTargetOcidAndLikerAccountId - 존재 여부 확인
 * </ul>
 */
@DisplayName("CharacterLikeRepository 통합 테스트")
class CharacterLikeRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    lateinit var characterLikeRepository: CharacterLikeRepository

    @Nested
    @DisplayName("findByTargetOcidAndLikerAccountId")
    inner class FindByTargetOcidAndLikerAccountId {

        @Test
        @DisplayName("조건에 맞는 좋아요를 찾는다")
        fun `returns like when found`() {
            // Given
            val targetOcid = "char-001"
            val likerAccountId = "user-001"
            val like = CharacterLike.of(targetOcid, likerAccountId)
            characterLikeRepository.save(like)
            flushAndClear()

            // When
            val found = characterLikeRepository.findByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)

            // Then
            assertThat(found).isNotNull
            assertThat(found!!.targetOcid).isEqualTo(targetOcid)
            assertThat(found.likerAccountId).isEqualTo(likerAccountId)
            assertThat(found.id).isNotNull()
        }

        @Test
        @DisplayName("조건에 맞는 좋아요가 없으면 null을 반환한다")
        fun `returns null when not found`() {
            // When
            val found = characterLikeRepository.findByTargetOcidAndLikerAccountId("nonexistent-char", "nonexistent-user")

            // Then
            assertThat(found).isNull()
        }
    }

    @Nested
    @DisplayName("findByLikerAccountId")
    inner class FindByLikerAccountId {

        @Test
        @DisplayName("사용자가 좋아요한 캐릭터 목록을 최신순으로 반환한다")
        fun `returns list of likes ordered by createdAt desc`() {
            // Given
            val likerAccountId = "user-002"
            val like1 = characterLikeRepository.save(CharacterLike.of("char-001", likerAccountId))
            Thread.sleep(10) // 타임스탬프 차이 보장
            val like2 = characterLikeRepository.save(CharacterLike.of("char-002", likerAccountId))
            Thread.sleep(10)
            val like3 = characterLikeRepository.save(CharacterLike.of("char-003", likerAccountId))
            flushAndClear()

            // When
            val likes = characterLikeRepository.findByLikerAccountId(likerAccountId)

            // Then
            assertThat(likes).hasSize(3)
            assertThat(likes[0].targetOcid).isEqualTo("char-003") // 최신
            assertThat(likes[1].targetOcid).isEqualTo("char-002")
            assertThat(likes[2].targetOcid).isEqualTo("char-001") // 오래된
        }

        @Test
        @DisplayName("좋아요한 캐릭터가 없으면 빈 목록을 반환한다")
        fun `returns empty list when no likes exist`() {
            // When
            val likes = characterLikeRepository.findByLikerAccountId("nonexistent-user")

            // Then
            assertThat(likes).isEmpty()
        }
    }

    @Nested
    @DisplayName("findByTargetOcid")
    inner class FindByTargetOcid {

        @Test
        @DisplayName("캐릭터를 좋아요한 사용자 목록을 최신순으로 반환한다")
        fun `returns list of likes ordered by createdAt desc`() {
            // Given
            val targetOcid = "char-003"
            val like1 = characterLikeRepository.save(CharacterLike.of(targetOcid, "user-001"))
            Thread.sleep(10)
            val like2 = characterLikeRepository.save(CharacterLike.of(targetOcid, "user-002"))
            Thread.sleep(10)
            val like3 = characterLikeRepository.save(CharacterLike.of(targetOcid, "user-003"))
            flushAndClear()

            // When
            val likes = characterLikeRepository.findByTargetOcid(targetOcid)

            // Then
            assertThat(likes).hasSize(3)
            assertThat(likes[0].likerAccountId).isEqualTo("user-003") // 최신
            assertThat(likes[1].likerAccountId).isEqualTo("user-002")
            assertThat(likes[2].likerAccountId).isEqualTo("user-001") // 오래된
        }

        @Test
        @DisplayName("좋아요한 사용자가 없으면 빈 목록을 반환한다")
        fun `returns empty list when no likes exist`() {
            // When
            val likes = characterLikeRepository.findByTargetOcid("nonexistent-char")

            // Then
            assertThat(likes).isEmpty()
        }
    }

    @Nested
    @DisplayName("save")
    inner class Save {

        @Test
        @DisplayName("새로운 좋아요를 생성하고 ID를 할당한다")
        fun `creates new like and assigns id`() {
            // Given
            val like = CharacterLike.of("char-004", "user-004")

            // When
            val saved = characterLikeRepository.save(like)
            flushAndClear()

            // Then
            assertThat(saved.id).isNotNull()
            assertThat(saved.targetOcid).isEqualTo("char-004")
            assertThat(saved.likerAccountId).isEqualTo("user-004")
            assertThat(saved.createdAt).isNotNull()
        }

        @Test
        @DisplayName("저장된 좋아요를 조회할 수 있다")
        fun `saved like can be retrieved`() {
            // Given
            val like = characterLikeRepository.save(CharacterLike.of("char-005", "user-005"))
            flushAndClear()

            // When
            val found = characterLikeRepository.findByTargetOcidAndLikerAccountId("char-005", "user-005")

            // Then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(like.id)
        }
    }

    @Nested
    @DisplayName("delete")
    inner class Delete {

        @Test
        @DisplayName("좋아요를 삭제한다")
        fun `deletes like`() {
            // Given
            val like = characterLikeRepository.save(CharacterLike.of("char-006", "user-006"))
            flushAndClear()

            // When
            characterLikeRepository.delete(like)
            flushAndClear()

            // Then
            val found = characterLikeRepository.findByTargetOcidAndLikerAccountId("char-006", "user-006")
            assertThat(found).isNull()
        }

        @Test
        @DisplayName("이미 삭제된 좋아요를 삭제해도 예외가 발생하지 않는다")
        fun `deleting non-existent like does not throw exception`() {
            // Given
            val like = CharacterLike.of("nonexistent-char", "nonexistent-user")

            // When & Then - 예외가 발생하지 않음
            characterLikeRepository.delete(like)
            flushAndClear()
        }
    }

    @Nested
    @DisplayName("deleteByTargetOcidAndLikerAccountId")
    inner class DeleteByTargetOcidAndLikerAccountId {

        @Test
        @DisplayName("조건에 맞는 좋아요를 삭제한다")
        fun `deletes like by target and liker`() {
            // Given
            characterLikeRepository.save(CharacterLike.of("char-007", "user-007"))
            flushAndClear()

            // When
            characterLikeRepository.deleteByTargetOcidAndLikerAccountId("char-007", "user-007")
            flushAndClear()

            // Then
            val found = characterLikeRepository.findByTargetOcidAndLikerAccountId("char-007", "user-007")
            assertThat(found).isNull()
        }

        @Test
        @DisplayName("조건에 맞는 좋아요가 없어도 예외가 발생하지 않는다")
        fun `deleting non-existent like does not throw exception`() {
            // When & Then - 예외가 발생하지 않음
            characterLikeRepository.deleteByTargetOcidAndLikerAccountId("nonexistent-char", "nonexistent-user")
            flushAndClear()
        }
    }

    @Nested
    @DisplayName("countByTargetOcid")
    inner class CountByTargetOcid {

        @Test
        @DisplayName("캐릭터의 좋아요 개수를 반환한다")
        fun `returns count of likes for character`() {
            // Given
            val targetOcid = "char-008"
            characterLikeRepository.save(CharacterLike.of(targetOcid, "user-001"))
            characterLikeRepository.save(CharacterLike.of(targetOcid, "user-002"))
            characterLikeRepository.save(CharacterLike.of(targetOcid, "user-003"))
            flushAndClear()

            // When
            val count = characterLikeRepository.countByTargetOcid(targetOcid)

            // Then
            assertThat(count).isEqualTo(3L)
        }

        @Test
        @DisplayName("좋아요가 없으면 0을 반환한다")
        fun `returns 0 when no likes exist`() {
            // When
            val count = characterLikeRepository.countByTargetOcid("nonexistent-char")

            // Then
            assertThat(count).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("countByLikerAccountId")
    inner class CountByLikerAccountId {

        @Test
        @DisplayName("사용자의 좋아요 개수를 반환한다")
        fun `returns count of likes by user`() {
            // Given
            val likerAccountId = "user-009"
            characterLikeRepository.save(CharacterLike.of("char-001", likerAccountId))
            characterLikeRepository.save(CharacterLike.of("char-002", likerAccountId))
            characterLikeRepository.save(CharacterLike.of("char-003", likerAccountId))
            characterLikeRepository.save(CharacterLike.of("char-004", likerAccountId))
            characterLikeRepository.save(CharacterLike.of("char-005", likerAccountId))
            flushAndClear()

            // When
            val count = characterLikeRepository.countByLikerAccountId(likerAccountId)

            // Then
            assertThat(count).isEqualTo(5L)
        }

        @Test
        @DisplayName("좋아요가 없으면 0을 반환한다")
        fun `returns 0 when no likes exist`() {
            // When
            val count = characterLikeRepository.countByLikerAccountId("nonexistent-user")

            // Then
            assertThat(count).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("existsByTargetOcidAndLikerAccountId")
    inner class ExistsByTargetOcidAndLikerAccountId {

        @Test
        @DisplayName("좋아요가 존재하면 true를 반환한다")
        fun `returns true when like exists`() {
            // Given
            val targetOcid = "char-010"
            val likerAccountId = "user-010"
            characterLikeRepository.save(CharacterLike.of(targetOcid, likerAccountId))
            flushAndClear()

            // When
            val exists = characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)

            // Then
            assertThat(exists).isTrue
        }

        @Test
        @DisplayName("좋아요가 존재하지 않으면 false를 반환한다")
        fun `returns false when like does not exist`() {
            // When
            val exists = characterLikeRepository.existsByTargetOcidAndLikerAccountId("nonexistent-char", "nonexistent-user")

            // Then
            assertThat(exists).isFalse
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    inner class ComplexScenarios {

        @Test
        @DisplayName("동일한 사용자가 여러 캐릭터를 좋아요할 수 있다")
        fun `user can like multiple characters`() {
            // Given
            val userId = "user-multi"
            characterLikeRepository.save(CharacterLike.of("char-001", userId))
            characterLikeRepository.save(CharacterLike.of("char-002", userId))
            characterLikeRepository.save(CharacterLike.of("char-003", userId))
            flushAndClear()

            // When
            val userLikes = characterLikeRepository.findByLikerAccountId(userId)

            // Then
            assertThat(userLikes).hasSize(3)
            assertThat(characterLikeRepository.countByLikerAccountId(userId)).isEqualTo(3L)
        }

        @Test
        @DisplayName("동일한 캐릭터를 여러 사용자가 좋아요할 수 있다")
        fun `character can be liked by multiple users`() {
            // Given
            val charOcid = "char-multi"
            characterLikeRepository.save(CharacterLike.of(charOcid, "user-001"))
            characterLikeRepository.save(CharacterLike.of(charOcid, "user-002"))
            characterLikeRepository.save(CharacterLike.of(charOcid, "user-003"))
            flushAndClear()

            // When
            val charLikes = characterLikeRepository.findByTargetOcid(charOcid)

            // Then
            assertThat(charLikes).hasSize(3)
            assertThat(characterLikeRepository.countByTargetOcid(charOcid)).isEqualTo(3L)
        }

        @Test
        @DisplayName("좋아요를 생성하고 삭제할 수 있다")
        fun `like can be created and deleted`() {
            // Given
            val targetOcid = "char-lifecycle"
            val likerAccountId = "user-lifecycle"
            val like = characterLikeRepository.save(CharacterLike.of(targetOcid, likerAccountId))
            flushAndClear()

            // Then - 생성 확인
            assertThat(characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)).isTrue
            assertThat(characterLikeRepository.countByTargetOcid(targetOcid)).isEqualTo(1L)
            assertThat(characterLikeRepository.countByLikerAccountId(likerAccountId)).isEqualTo(1L)

            // When - 삭제
            characterLikeRepository.delete(like)
            flushAndClear()

            // Then - 삭제 확인
            assertThat(characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)).isFalse
            assertThat(characterLikeRepository.countByTargetOcid(targetOcid)).isEqualTo(0L)
            assertThat(characterLikeRepository.countByLikerAccountId(likerAccountId)).isEqualTo(0L)
        }

        @Test
        @DisplayName("좋아요를 조건으로 삭제하고 다시 생성할 수 있다")
        fun `like can be deleted by condition and recreated`() {
            // Given
            val targetOcid = "char-recreate"
            val likerAccountId = "user-recreate"
            characterLikeRepository.save(CharacterLike.of(targetOcid, likerAccountId))
            flushAndClear()

            // When - 삭제
            characterLikeRepository.deleteByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)
            flushAndClear()

            // Then - 삭제 확인
            assertThat(characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)).isFalse

            // When - 재생성
            val newLike = characterLikeRepository.save(CharacterLike.of(targetOcid, likerAccountId))
            flushAndClear()

            // Then - 재생성 확인
            assertThat(newLike.id).isNotNull()
            assertThat(characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId)).isTrue
        }
    }
}

package maple.expectation.application.service.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import maple.expectation.core.domain.model.character.CharacterId;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.domain.model.like.LikeToggleResult;
import maple.expectation.core.domain.model.like.LikeToggleWithCount;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.SelfLikeNotAllowedException;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LikeToggleService Pure Unit Test Suite
 *
 * <p><b>Purpose:</b> Tests the like toggle logic without any Spring dependencies.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Self-like prevention
 *   <li>Like toggle behavior (LIKED, UNLIKED, LIKED again)
 *   <li>Counter reflects correct count after toggle
 *   <li>Non-existent IGN throws CharacterNotFoundException
 *   <li>Concurrent toggle safety (ON CONFLICT DO NOTHING)
 * </ul>
 *
 * <p><b>Pure Unit Test:</b> No Spring, no @SpringBootTest. Uses Mockito for dependency mocking.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit: LikeToggleService")
class LikeToggleServiceTest {

  private static final String TARGET_IGN = "TestCharacter";
  private static final String TARGET_OCID = "ocid123";
  private static final String LIKER_ACCOUNT_ID = "account456";
  private static final Set<String> MY_OCIDS = Set.of("ocid001", "ocid002");

  @Mock private maple.expectation.domain.repository.CharacterLikeRepository characterLikeRepository;

  @Mock private maple.expectation.core.port.out.CharacterOcidPort characterOcidPort;

  private maple.expectation.infrastructure.executor.LogicExecutor executor;

  private LikeToggleService likeToggleService;

  @BeforeEach
  void setUp() {
    executor = TestLogicExecutors.passThrough();

    likeToggleService =
        new LikeToggleService(
            characterLikeRepository, characterOcidPort, executor);

    // Default mock behaviors
    lenient().when(characterOcidPort.resolveOcid(TARGET_IGN)).thenReturn(TARGET_OCID);
    lenient()
        .when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    lenient()
        .when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1);
    lenient().when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(1L);
  }

  @Test
  @DisplayName("GIVEN: self-like attempt WHEN: toggleLike THEN: throws SelfLikeNotAllowedException")
  void given_selfLike_when_toggleLike_then_throwsException() {
    // given: target OCID is in my OCIDs (self-like)
    Set<String> myOcidsWithTarget = Set.of("ocid001", "ocid002", TARGET_OCID);

    // when & then
    assertThatThrownBy(() -> likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, myOcidsWithTarget))
        .isInstanceOf(SelfLikeNotAllowedException.class);

    // Verify no database operations were attempted
    verify(characterLikeRepository, never()).existsByTargetOcidAndLikerAccountId(any(), any());
    verify(characterLikeRepository, never()).insertIfAbsent(any(), any());
  }

  @Test
  @DisplayName("GIVEN: first like WHEN: toggleLike THEN: returns LIKED and count is 1")
  void given_firstLike_when_toggleLike_then_returnsLiked_andCountIsOne() {
    // given: like does not exist (default mock setup)

    // when
    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then
    assertThat(result).isEqualTo(LikeToggleResult.LIKED);
    verify(characterLikeRepository).insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: first like WHEN: toggleLikeWithCount THEN: returns LIKED with count")
  void given_firstLike_when_toggleLikeWithCount_then_returnsLikedWithCount() {
    // given: like does not exist, count will be 1
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(1L);

    // when
    LikeToggleWithCount result =
        likeToggleService.toggleLikeWithCount(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then
    assertThat(result.getResult()).isEqualTo(LikeToggleResult.LIKED);
    assertThat(result.getLikeCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("GIVEN: existing like WHEN: toggleLike THEN: returns UNLIKED")
  void given_existingLike_when_toggleLike_then_returnsUnliked() {
    // given: like already exists
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);

    // when
    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then
    assertThat(result).isEqualTo(LikeToggleResult.UNLIKED);
    verify(characterLikeRepository).deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: toggle three times WHEN: toggleLike THEN: LIKED -> UNLIKED -> LIKED")
  void given_toggleThreeTimes_when_toggleLike_then_togglesCorrectly() {
    // First call: no like exists -> LIKED
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(1);

    LikeToggleResult result1 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result1).isEqualTo(LikeToggleResult.LIKED);

    // Second call: like exists -> UNLIKED
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);

    LikeToggleResult result2 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result2).isEqualTo(LikeToggleResult.UNLIKED);

    // Third call: no like exists again -> LIKED
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(1);

    LikeToggleResult result3 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result3).isEqualTo(LikeToggleResult.LIKED);
  }

  @Test
  @DisplayName("GIVEN: concurrent like insert WHEN: toggleLike THEN: handles ON CONFLICT DO NOTHING")
  void given_concurrentLikeInsert_when_toggleLike_then_handlesConflict() {
    // given: insertIfAbsent returns 0 (already inserted by concurrent request)
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(0);

    // when
    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then: still returns LIKED (idempotent behavior)
    assertThat(result).isEqualTo(LikeToggleResult.LIKED);
    verify(characterLikeRepository).insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: non-existent IGN WHEN: toggleLike THEN: throws CharacterNotFoundException")
  void given_nonExistentIgn_when_toggleLike_then_throwsCharacterNotFoundException() {
    // given: OCID resolution returns null (character not found)
    when(characterOcidPort.resolveOcid(TARGET_IGN))
        .thenReturn(null);

    // when & then
    assertThatThrownBy(() -> likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS))
        .isInstanceOf(CharacterNotFoundException.class);

    // Verify no database operations were attempted
    verify(characterLikeRepository, never()).existsByTargetOcidAndLikerAccountId(any(), any());
  }

  @Test
  @DisplayName("GIVEN: multiple likes WHEN: getLikeCount THEN: returns correct count")
  void given_multipleLikes_when_getLikeCount_then_returnsCorrectCount() {
    // given
    long expectedCount = 42L;
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(expectedCount);

    // when
    long count = likeToggleService.getLikeCount(TARGET_IGN);

    // then
    assertThat(count).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("GIVEN: existing like WHEN: isLiked THEN: returns true")
  void given_existingLike_when_isLiked_then_returnsTrue() {
    // given
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);

    // when
    boolean liked = likeToggleService.isLiked(TARGET_IGN, LIKER_ACCOUNT_ID);

    // then
    assertThat(liked).isTrue();
  }

  @Test
  @DisplayName("GIVEN: no existing like WHEN: isLiked THEN: returns false")
  void given_noExistingLike_when_isLiked_then_returnsFalse() {
    // given: default mock behavior returns false

    // when
    boolean liked = likeToggleService.isLiked(TARGET_IGN, LIKER_ACCOUNT_ID);

    // then
    assertThat(liked).isFalse();
  }

  @Test
  @DisplayName("GIVEN: toggle with count WHEN: toggleLikeWithCount THEN: returns correct count")
  void given_toggleWithCount_when_toggleLikeWithCount_then_returnsCorrectCount() {
    // given: existing like, will unlike
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(99L);

    // when
    LikeToggleWithCount result =
        likeToggleService.toggleLikeWithCount(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then
    assertThat(result.getResult()).isEqualTo(LikeToggleResult.UNLIKED);
    assertThat(result.getLikeCount()).isEqualTo(99L);
  }

  @Test
  @DisplayName("GIVEN: concurrent unlike WHEN: toggleLike THEN: handles gracefully")
  void given_concurrentUnlike_when_toggleLike_then_handlesGracefully() {
    // given: delete returns 0 (already deleted by concurrent request)
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(0L);

    // when
    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    // then: still returns UNLIKED (idempotent behavior)
    assertThat(result).isEqualTo(LikeToggleResult.UNLIKED);
  }
}

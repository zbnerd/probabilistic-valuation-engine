package maple.expectation.infrastructure.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.core.domain.model.like.LikeToggleResult;
import maple.expectation.core.domain.model.like.LikeToggleWithCount;
import maple.expectation.core.port.out.CharacterOcidPort;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.SelfLikeNotAllowedException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.CharacterLikeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit: LikeToggleService")
class LikeToggleServiceTest {

  private static final String TARGET_IGN = "TestCharacter";
  private static final String TARGET_OCID = "ocid123";
  private static final String LIKER_ACCOUNT_ID = "account456";
  private static final Set<String> MY_OCIDS = Set.of("ocid001", "ocid002");

  @Mock private CharacterLikeRepository characterLikeRepository;

  @Mock private CharacterOcidPort characterOcidPort;

  private LogicExecutor executor;

  private LikeToggleService likeToggleService;

  @BeforeEach
  void setUp() {
    executor = passThroughExecutor();

    likeToggleService = new LikeToggleService(characterLikeRepository, characterOcidPort, executor);

    lenient().when(characterOcidPort.resolveOcid(TARGET_IGN)).thenReturn(TARGET_OCID);
    lenient()
        .when(
            characterLikeRepository.existsByTargetOcidAndLikerAccountId(
                TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    lenient()
        .when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1);
    lenient().when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(1L);
  }

  @Test
  @DisplayName("GIVEN: self-like attempt WHEN: toggleLike THEN: throws SelfLikeNotAllowedException")
  void given_selfLike_when_toggleLike_then_throwsException() {
    Set<String> myOcidsWithTarget = Set.of("ocid001", "ocid002", TARGET_OCID);

    assertThatThrownBy(
            () -> likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, myOcidsWithTarget))
        .isInstanceOf(SelfLikeNotAllowedException.class);

    verify(characterLikeRepository, never()).existsByTargetOcidAndLikerAccountId(any(), any());
    verify(characterLikeRepository, never()).insertIfAbsent(any(), any());
  }

  @Test
  @DisplayName("GIVEN: first like WHEN: toggleLike THEN: returns LIKED and count is 1")
  void given_firstLike_when_toggleLike_then_returnsLiked_andCountIsOne() {
    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result).isEqualTo(LikeToggleResult.LIKED);
    verify(characterLikeRepository).insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: first like WHEN: toggleLikeWithCount THEN: returns LIKED with count")
  void given_firstLike_when_toggleLikeWithCount_then_returnsLikedWithCount() {
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(1L);

    LikeToggleWithCount result =
        likeToggleService.toggleLikeWithCount(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result.getResult()).isEqualTo(LikeToggleResult.LIKED);
    assertThat(result.getLikeCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("GIVEN: existing like WHEN: toggleLike THEN: returns UNLIKED")
  void given_existingLike_when_toggleLike_then_returnsUnliked() {
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);

    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result).isEqualTo(LikeToggleResult.UNLIKED);
    verify(characterLikeRepository)
        .deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: toggle three times WHEN: toggleLike THEN: LIKED -> UNLIKED -> LIKED")
  void given_toggleThreeTimes_when_toggleLike_then_togglesCorrectly() {
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(1);

    LikeToggleResult result1 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result1).isEqualTo(LikeToggleResult.LIKED);

    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);

    LikeToggleResult result2 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result2).isEqualTo(LikeToggleResult.UNLIKED);

    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(false);
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(1);

    LikeToggleResult result3 = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);
    assertThat(result3).isEqualTo(LikeToggleResult.LIKED);
  }

  @Test
  @DisplayName(
      "GIVEN: concurrent like insert WHEN: toggleLike THEN: handles ON CONFLICT DO NOTHING")
  void given_concurrentLikeInsert_when_toggleLike_then_handlesConflict() {
    when(characterLikeRepository.insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID)).thenReturn(0);

    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result).isEqualTo(LikeToggleResult.LIKED);
    verify(characterLikeRepository).insertIfAbsent(TARGET_OCID, LIKER_ACCOUNT_ID);
  }

  @Test
  @DisplayName("GIVEN: non-existent IGN WHEN: toggleLike THEN: throws CharacterNotFoundException")
  void given_nonExistentIgn_when_toggleLike_then_throwsCharacterNotFoundException() {
    when(characterOcidPort.resolveOcid(TARGET_IGN)).thenReturn(null);

    assertThatThrownBy(() -> likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS))
        .isInstanceOf(CharacterNotFoundException.class);

    verify(characterLikeRepository, never()).existsByTargetOcidAndLikerAccountId(any(), any());
  }

  @Test
  @DisplayName("GIVEN: multiple likes WHEN: getLikeCount THEN: returns correct count")
  void given_multipleLikes_when_getLikeCount_then_returnsCorrectCount() {
    long expectedCount = 42L;
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(expectedCount);

    long count = likeToggleService.getLikeCount(TARGET_IGN);

    assertThat(count).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("GIVEN: existing like WHEN: isLiked THEN: returns true")
  void given_existingLike_when_isLiked_then_returnsTrue() {
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);

    boolean liked = likeToggleService.isLiked(TARGET_IGN, LIKER_ACCOUNT_ID);

    assertThat(liked).isTrue();
  }

  @Test
  @DisplayName("GIVEN: no existing like WHEN: isLiked THEN: returns false")
  void given_noExistingLike_when_isLiked_then_returnsFalse() {
    boolean liked = likeToggleService.isLiked(TARGET_IGN, LIKER_ACCOUNT_ID);

    assertThat(liked).isFalse();
  }

  @Test
  @DisplayName("GIVEN: toggle with count WHEN: toggleLikeWithCount THEN: returns correct count")
  void given_toggleWithCount_when_toggleLikeWithCount_then_returnsCorrectCount() {
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(1L);
    when(characterLikeRepository.countByTargetOcid(TARGET_OCID)).thenReturn(99L);

    LikeToggleWithCount result =
        likeToggleService.toggleLikeWithCount(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result.getResult()).isEqualTo(LikeToggleResult.UNLIKED);
    assertThat(result.getLikeCount()).isEqualTo(99L);
  }

  @Test
  @DisplayName("GIVEN: concurrent unlike WHEN: toggleLike THEN: handles gracefully")
  void given_concurrentUnlike_when_toggleLike_then_handlesGracefully() {
    when(characterLikeRepository.existsByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(true);
    when(characterLikeRepository.deleteByTargetOcidAndLikerAccountId(TARGET_OCID, LIKER_ACCOUNT_ID))
        .thenReturn(0L);

    LikeToggleResult result = likeToggleService.toggleLike(TARGET_IGN, LIKER_ACCOUNT_ID, MY_OCIDS);

    assertThat(result).isEqualTo(LikeToggleResult.UNLIKED);
  }

  private LogicExecutor passThroughExecutor() {
    LogicExecutor logicExecutor = mock(LogicExecutor.class);
    lenient()
        .when(logicExecutor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
        .thenAnswer(invocation -> invocation.<ThrowingSupplier<?>>getArgument(0).get());
    lenient()
        .when(
            logicExecutor.executeOrDefault(
                any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(invocation -> invocation.<ThrowingSupplier<?>>getArgument(0).get());
    return logicExecutor;
  }
}

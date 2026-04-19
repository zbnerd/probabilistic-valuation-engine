package maple.expectation.application.service.like;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.like.LikeToggleResult;
import maple.expectation.core.domain.model.like.LikeToggleWithCount;
import maple.expectation.core.port.inbound.LikeTogglePort;
import maple.expectation.core.port.out.CharacterOcidPort;
import maple.expectation.domain.repository.CharacterLikeRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.SelfLikeNotAllowedException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 토글 서비스 (Direct DB 방식 - ADR-029)
 *
 * <p>단일 트랜잭션으로 좋아요 토글 처리. Scale-out ready, ACID 보장.
 *
 * <p>흐름:
 *
 * <ol>
 *   <li>IGN → OCID 해석 (CharacterOcidPort + @Cacheable)
 *   <li>Self-like 방지 검증
 *   <li>존재 여부 확인 → INSERT/DELETE + count 증감
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeToggleService implements LikeTogglePort {

  private final CharacterLikeRepository characterLikeRepository;
  private final CharacterOcidPort characterOcidPort;
  private final LogicExecutor executor;

  /**
   * 좋아요 토글
   *
   * <p>좋아요가 없으면 추가, 있으면 취소.
   *
   * @param targetUserIgn 좋아요 대상 캐릭터 닉네임
   * @param likerAccountId 좋아요를 누르는 계정 ID
   * @param myOcids 요청자가 소유한 캐릭터 OCID 집합
   * @return LIKED 또는 UNLIKED
   * @throws SelfLikeNotAllowedException 자기 캐릭터에 좋아요를 누를 때
   */
  @Transactional("transactionManager")
  public LikeToggleResult toggleLike(
      String targetUserIgn, String likerAccountId, Set<String> myOcids) {
    return toggleLikeWithCount(targetUserIgn, likerAccountId, myOcids).getResult();
  }

  @Override
  @Transactional("transactionManager")
  public LikeToggleWithCount toggleLikeWithCount(
      String targetUserIgn, String likerAccountId, Set<String> myOcids) {
    String targetOcid = resolveTargetOcid(targetUserIgn);
    validateNotSelfLike(targetUserIgn, targetOcid, myOcids);

    return executor.execute(
        () -> {
          LikeToggleResult result = toggleRelation(targetOcid, targetUserIgn, likerAccountId);
          long count = characterLikeRepository.countByTargetOcid(targetOcid);
          return new LikeToggleWithCount(result, count);
        },
        TaskContext.of("LikeToggleService", "ToggleLikeWithCount", targetUserIgn));
  }

  /**
   * 좋아요 상태 조회
   *
   * @param targetUserIgn 조회할 캐릭터 닉네임
   * @param likerAccountId 조회자 계정 ID
   * @return 좋아요 여부
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public boolean isLiked(String targetUserIgn, String likerAccountId) {
    String targetOcid = resolveTargetOcid(targetUserIgn);

    return executor.executeOrDefault(
        () ->
            characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId),
        false,
        TaskContext.of("LikeToggleService", "IsLiked", targetUserIgn));
  }

  /**
   * 좋아요 수 조회
   *
   * <p><b>Eventual Consistency:</b> 이 메서드는 DB에서 직접 조회하므로 캐시 갱신 지연(~10ms)이 없어 가장 최신값을 반환합니다.
   *
   * <p>좋아요 토글 후 즉시 호출하면 트랜잭션 커밋 후의 최신값이 반환됩니다.
   *
   * @param targetUserIgn 조회할 캐릭터 닉네임
   * @return 좋아요 수
   */
  @Transactional(value = "transactionManager", readOnly = true)
  public long getLikeCount(String targetUserIgn) {
    String targetOcid = resolveTargetOcid(targetUserIgn);

    return executor.executeOrDefault(
        () -> characterLikeRepository.countByTargetOcid(targetOcid),
        0L,
        TaskContext.of("LikeToggleService", "GetLikeCount", targetUserIgn));
  }

  private String resolveTargetOcid(String targetUserIgn) {
    String ocid = characterOcidPort.resolveOcid(targetUserIgn);
    if (ocid == null) {
      throw new CharacterNotFoundException(targetUserIgn);
    }
    return ocid;
  }

  private void validateNotSelfLike(String userIgn, String targetOcid, Set<String> myOcids) {
    if (myOcids.contains(targetOcid)) {
      throw new SelfLikeNotAllowedException(userIgn, targetOcid);
    }
  }

  private LikeToggleResult toggleRelation(
      String targetOcid, String targetUserIgn, String likerAccountId) {
    boolean exists =
        characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId);
    if (!exists) {
      return likeCharacter(targetOcid, targetUserIgn, likerAccountId);
    }
    return unlikeCharacter(targetOcid, targetUserIgn, likerAccountId);
  }

  private LikeToggleResult likeCharacter(
      String targetOcid, String targetUserIgn, String likerAccountId) {
    int inserted = characterLikeRepository.insertIfAbsent(targetOcid, likerAccountId);
    if (inserted > 0) {
      log.info("Like added: target={}, liker={}", targetUserIgn, maskId(likerAccountId));
    } else {
      log.debug("Like duplicate (concurrent): target={}", targetUserIgn);
    }
    return LikeToggleResult.LIKED;
  }

  private LikeToggleResult unlikeCharacter(
      String targetOcid, String targetUserIgn, String likerAccountId) {
    long deleted =
        characterLikeRepository.deleteByTargetOcidAndLikerAccountId(targetOcid, likerAccountId);
    if (deleted > 0) {
      log.info("Like removed: target={}", targetUserIgn);
    } else {
      log.debug("Like already removed (concurrent): target={}", targetUserIgn);
    }
    return LikeToggleResult.UNLIKED;
  }

  private String maskId(String id) {
    if (id == null || id.length() < 8) return "****";
    return id.substring(0, 4) + "****";
  }
}

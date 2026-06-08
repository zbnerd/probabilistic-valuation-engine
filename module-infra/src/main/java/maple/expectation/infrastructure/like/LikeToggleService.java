package maple.expectation.infrastructure.like;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.like.LikeToggleResult;
import maple.expectation.core.domain.model.like.LikeToggleWithCount;
import maple.expectation.core.port.inbound.LikeTogglePort;
import maple.expectation.core.port.out.CharacterOcidPort;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.SelfLikeNotAllowedException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.CharacterLikeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 토글 서비스 (Direct DB 방식 - ADR-029)
 *
 * <p>단일 트랜잭션으로 좋아요 토글 처리. Scale-out ready, ACID 보장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeToggleService implements LikeTogglePort {

  private final CharacterLikeRepository characterLikeRepository;
  private final CharacterOcidPort characterOcidPort;
  private final LogicExecutor executor;

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

  @Transactional(value = "transactionManager", readOnly = true)
  public boolean isLiked(String targetUserIgn, String likerAccountId) {
    String targetOcid = resolveTargetOcid(targetUserIgn);

    return executor.executeOrDefault(
        () ->
            characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId),
        false,
        TaskContext.of("LikeToggleService", "IsLiked", targetUserIgn));
  }

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

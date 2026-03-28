package maple.expectation.application.service.like;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.like.CharacterLike;
import maple.expectation.domain.repository.CharacterLikeRepository;
import maple.expectation.domain.repository.GameCharacterRepository;
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
 * <ol>
 *   <li>IGN → OCID 해석 (OcidResolutionService + Caffeine 캐시)</li>
 *   <li>Self-like 방지 검증</li>
 *   <li>존재 여부 확인 → INSERT/DELETE + count 증감</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeToggleService {

    private final CharacterLikeRepository characterLikeRepository;
    private final GameCharacterRepository gameCharacterRepository;
    private final OcidResolutionService ocidResolutionService;
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
    public LikeToggleResult toggleLike(String targetUserIgn, String likerAccountId, Set<String> myOcids) {
        String targetOcid = resolveTargetOcid(targetUserIgn);
        validateNotSelfLike(targetUserIgn, targetOcid, myOcids);

        return executor.execute(
                () -> toggleRelation(targetOcid, targetUserIgn, likerAccountId),
                TaskContext.of("LikeToggleService", "ToggleLike", targetUserIgn)
        );
    }

    /**
     * 좋아요 상태 조회
     *
     * @param targetUserIgn 조회할 캐릭터 닉네임
     * @param likerAccountId 조회자 계정 ID
     * @return 좋아요 여부
     */
    public boolean isLiked(String targetUserIgn, String likerAccountId) {
        String targetOcid = resolveTargetOcid(targetUserIgn);

        return executor.executeOrDefault(
                () -> characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, likerAccountId),
                false,
                TaskContext.of("LikeToggleService", "IsLiked", targetUserIgn)
        );
    }

    /**
     * 좋아요 수 조회
     *
     * @param targetUserIgn 조회할 캐릭터 닉네임
     * @return 좋아요 수
     */
    public long getLikeCount(String targetUserIgn) {
        String targetOcid = resolveTargetOcid(targetUserIgn);

        return executor.executeOrDefault(
                () -> characterLikeRepository.countByTargetOcid(targetOcid),
                0L,
                TaskContext.of("LikeToggleService", "GetLikeCount", targetUserIgn)
        );
    }

    private String resolveTargetOcid(String targetUserIgn) {
        return ocidResolutionService.resolveOcid(targetUserIgn);
    }

    private void validateNotSelfLike(String userIgn, String targetOcid, Set<String> myOcids) {
        if (myOcids.contains(targetOcid)) {
            throw new SelfLikeNotAllowedException(userIgn, targetOcid);
        }
    }

    private LikeToggleResult toggleRelation(String targetOcid, String targetUserIgn, String likerAccountId) {
        CharacterLike existing = characterLikeRepository.findByTargetOcidAndLikerAccountId(targetOcid, likerAccountId);
        if (existing == null) {
            return likeCharacter(targetOcid, targetUserIgn, likerAccountId);
        }
        return unlikeCharacter(existing, targetUserIgn);
    }

    private LikeToggleResult likeCharacter(String targetOcid, String targetUserIgn, String likerAccountId) {
        characterLikeRepository.save(CharacterLike.create(targetOcid, likerAccountId));
        gameCharacterRepository.incrementLikeCount(targetUserIgn, 1);
        log.debug("Like added: target={}, liker={}", targetUserIgn, maskId(likerAccountId));
        return LikeToggleResult.LIKED;
    }

    private LikeToggleResult unlikeCharacter(CharacterLike existing, String targetUserIgn) {
        characterLikeRepository.delete(existing);
        gameCharacterRepository.incrementLikeCount(targetUserIgn, -1);
        log.debug("Like removed: target={}, liker={}", targetUserIgn, maskId(existing.getLikerAccountId()));
        return LikeToggleResult.UNLIKED;
    }

    private String maskId(String id) {
        if (id == null || id.length() < 8) return "****";
        return id.substring(0, 4) + "****";
    }
}

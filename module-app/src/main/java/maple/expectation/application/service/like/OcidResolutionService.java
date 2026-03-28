package maple.expectation.application.service.like;

import lombok.RequiredArgsConstructor;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.domain.repository.GameCharacterRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * IGN → OCID 해석 서비스
 *
 * <p>DB 조회 + Caffeine 캐시(30min TTL)로 IGN을 OCID로 변환.
 * 외부 API(NexonApiClient) 의존 없음.
 */
@Service
@RequiredArgsConstructor
public class OcidResolutionService {

    private final GameCharacterRepository gameCharacterRepository;
    private final LogicExecutor executor;

    /**
     * IGN을 OCID로 변환 (캐시 적용)
     *
     * @param userIgn 캐릭터 닉네임
     * @return OCID 문자열
     * @throws CharacterNotFoundException 캐릭터가 DB에 없을 때
     */
    @Cacheable(value = "ocidCache", key = "#userIgn", unless = "#result == null")
    public String resolveOcid(String userIgn) {
        return executor.execute(
                () -> {
                    GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);
                    if (character == null) {
                        throw new CharacterNotFoundException(userIgn);
                    }
                    return character.getCharacterId().value();
                },
                TaskContext.of("OcidResolutionService", "ResolveOcid", userIgn)
        );
    }
}

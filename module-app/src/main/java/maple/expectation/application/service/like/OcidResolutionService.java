package maple.expectation.application.service.like;

import lombok.RequiredArgsConstructor;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.domain.repository.GameCharacterRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;

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

    /**
     * 현재 시스템에 존재하는 모든 캐릭터의 OCID를 조회합니다.
     *
     * <p><b>사용처:</b> JwtAuthenticationFilter에서 myOcids를 생성하기 위해 사용
     *
     * <p><b>성능 고려사항:</b> 이 메서드는 DB에 존재하는 모든 캐릭터를 조회하므로
     * 호출 시점에 주의가 필요합니다. JwtAuthenticationFilter에서는 캐싱을 통해
     * 부하를 줄입니다.
     *
     * <p><b>제약사항 (P1):</b> 현재 구조에서는 game_character 테이블이 사용자의
     * API Key(fingerprint)를 저장하지 않습니다. 따라서 이 메서드는 DB에 존재하는
     * 모든 캐릭터를 반환합니다.
     *
     * <p>정확한 Self-Like 방지를 위해서는 game_character 테이블에 fingerprint 컬럼을
     * 추가하여 사용자가 실제로 소유한 캐릭터만 정확히 식별할 수 있어야 합니다.
     * 현재는 모든 캐릭터를 조회한 후 애플리케이션 레벨에서 필터링해야 하는 제약이 있습니다.
     *
     * @return 모든 캐릭터의 (IGN → OCID) 매핑
     */
    public java.util.Set<String> resolveAllOcids() {
        return executor.execute(
                () -> {
                    List<GameCharacter> characters = gameCharacterRepository.findAll();
                    return characters.stream()
                            .map(c -> c.getCharacterId().value())
                            .collect(java.util.stream.Collectors.toSet());
                },
                TaskContext.of("OcidResolutionService", "ResolveAllOcids")
        );
    }
}

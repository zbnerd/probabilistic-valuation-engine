package maple.expectation.application.service.like;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.port.out.CharacterOcidPort;
import maple.expectation.domain.repository.GameCharacterRepository;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * IGN → OCID 해석 서비스
 *
 * <p>CharacterOcidPort(Core Port)를 위임하여 단일 진실 공급원 유지. 캐시는
 * CharacterOcidAdapter의 @Cacheable("ocidCache")와 공유.
 */
@Service
@RequiredArgsConstructor
public class OcidResolutionService {

  private final CharacterOcidPort characterOcidPort;
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
          String ocid = characterOcidPort.resolveOcid(userIgn);
          if (ocid == null) {
            throw new CharacterNotFoundException(userIgn);
          }
          return ocid;
        },
        TaskContext.of("OcidResolutionService", "ResolveOcid", userIgn));
  }

  /**
   * IGN을 OCID로 변환 (캐시 적용), 캐릭터가 없으면 null 반환
   *
   * <p><b>사용처:</b> JwtAuthenticationFilter에서 사용 (예외 없이 null로 처리)
   *
   * @param userIgn 캐릭터 닉네임
   * @return OCID 문자열, 없으면 null
   */
  public String resolveOcidOrNull(String userIgn) {
    return executor.executeOrDefault(
        () -> characterOcidPort.resolveOcid(userIgn),
        null,
        TaskContext.of("OcidResolutionService", "ResolveOcidOrNull", userIgn));
  }

  /**
   * 현재 시스템에 존재하는 모든 캐릭터의 OCID를 조회합니다.
   *
   * <p><b>사용처:</b> JwtAuthenticationFilter에서 myOcids를 생성하기 위해 사용
   *
   * <p><b>성능 고려사항:</b> 이 메서드는 DB에 존재하는 모든 캐릭터를 조회하므로 호출 시점에 주의가 필요합니다. JwtAuthenticationFilter에서는
   * 캐싱을 통해 부하를 줄입니다.
   *
   * <p><b>제약사항 (P1):</b> 현재 구조에서는 game_character 테이블이 사용자의 API Key(fingerprint)를 저장하지 않습니다. 따라서 이
   * 메서드는 DB에 존재하는 모든 캐릭터를 반환합니다.
   *
   * <p>정확한 Self-Like 방지를 위해서는 game_character 테이블에 fingerprint 컬럼을 추가하여 사용자가 실제로 소유한 캐릭터만 정확히 식별할 수
   * 있어야 합니다. 현재는 모든 캐릭터를 조회한 후 애플리케이션 레벨에서 필터링해야 하는 제약이 있습니다.
   *
   * @return 모든 캐릭터의 (IGN → OCID) 매핑
   */
  public Set<String> resolveAllOcids() {
    return executor.execute(
        this::loadAllOcids, TaskContext.of("OcidResolutionService", "ResolveAllOcids"));
  }

  private Set<String> loadAllOcids() {
    List<GameCharacter> characters = gameCharacterRepository.findAll();
    return characters.stream().map(c -> c.getCharacterId().value()).collect(Collectors.toSet());
  }
}

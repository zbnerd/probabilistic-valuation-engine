package maple.expectation.infrastructure.like;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.core.port.out.CharacterOcidPort;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
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

  public String resolveOcidOrNull(String userIgn) {
    return executor.executeOrDefault(
        () -> characterOcidPort.resolveOcid(userIgn),
        null,
        TaskContext.of("OcidResolutionService", "ResolveOcidOrNull", userIgn));
  }

  public Set<String> resolveAllOcids() {
    return executor.execute(
        this::loadAllOcids, TaskContext.of("OcidResolutionService", "ResolveAllOcids"));
  }

  private Set<String> loadAllOcids() {
    List<GameCharacter> characters = gameCharacterRepository.findAll();
    return characters.stream().map(c -> c.getCharacterId().value()).collect(Collectors.toSet());
  }
}

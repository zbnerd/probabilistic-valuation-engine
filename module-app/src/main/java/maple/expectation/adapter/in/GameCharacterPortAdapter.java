package maple.expectation.adapter.in;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.GameCharacterPort;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.stereotype.Component;

/**
 * GameCharacterPort 구현체 (ADR-005)
 *
 * <p>책임: GameCharacterService에 위임(delegate)
 *
 * <p>위임 이유:
 *
 * <ul>
 *   <li>순환 의존성 해결: module-web → module-app → module-core
 *   <li>기존 Service 로직 재사용
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterPortAdapter implements GameCharacterPort {

  private final GameCharacterService gameCharacterService;

  @Override
  public boolean isNonExistent(String userIgn) {
    return gameCharacterService.isNonExistent(userIgn);
  }

  @Override
  public Optional<GameCharacter> getCharacterIfExist(String userIgn) {
    return gameCharacterService.getCharacterIfExist(userIgn);
  }

  @Override
  public GameCharacter createNewCharacter(String userIgn) {
    return gameCharacterService.createNewCharacter(userIgn);
  }

  @Override
  public String saveCharacter(GameCharacter character) {
    return gameCharacterService.saveCharacter(character);
  }

  @Override
  public GameCharacter getCharacterOrThrow(String userIgn) {
    return gameCharacterService.getCharacterOrThrow(userIgn);
  }

  @Override
  public GameCharacter enrichCharacterBasicInfo(GameCharacter character) {
    return gameCharacterService.enrichCharacterBasicInfo(character);
  }

  @Override
  public GameCharacter getCharacterForUpdate(String userIgn) {
    return gameCharacterService.getCharacterForUpdate(userIgn);
  }
}

package maple.expectation.application.service.character;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.character.notify.CharacterCreationListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

  private final GameCharacterService gameCharacterService;
  private final LogicExecutor executor;
  private final CharacterCreationListener characterCreationListener;

  /**
   * 캐릭터 조회 + 기본 정보 보강
   *
   * <p>expectation-sequence-diagram 패턴 적용:
   *
   * <ul>
   *   <li>Phase 2: Light Snapshot (캐릭터 조회)
   *   <li>Phase 4: Full Snapshot (기본 정보 보강 - worldName이 null이면 API 호출)
   * </ul>
   */
  public GameCharacter findCharacterByUserIgn(String userIgn) {
    String cleanUserIgn = userIgn.trim();
    TaskContext context = TaskContext.of("CharacterFacade", "FindCharacter", cleanUserIgn);

    return executor.execute(
        () -> {
          if (gameCharacterService.isNonExistent(cleanUserIgn)) {
            throw new CharacterNotFoundException(cleanUserIgn);
          }

          GameCharacter character =
              gameCharacterService
                  .getCharacterIfExist(cleanUserIgn)
                  .orElseGet(() -> waitForWorkerResult(cleanUserIgn));

          // Phase 4: 기본 정보 보강 (worldName이 null이면 API 호출 + 비동기 DB 저장)
          return gameCharacterService.enrichCharacterBasicInfo(character);
        },
        context);
  }

  /**
   * Character Creation Event-driven Wait (replaces Thread.sleep polling)
   *
   * <p>Uses PostgreSQL LISTEN/NOTIFY via CharacterCreationListener.
   * When a character is created, CharacterCreationService sends NOTIFY
   * and this method returns immediately via CompletableFuture.
   *
   * @see CharacterCreationNotifier
   * @see CharacterCreationListener
   */
  private GameCharacter waitForWorkerResult(String userIgn) {
    TaskContext context = TaskContext.of("CharacterFacade", "WaitWorker", userIgn);

    return executor.execute(
        () -> {
          log.info("📥 [AsyncWait] 캐릭터 조회 대기 시작: {}", userIgn);

          // Check if character already exists (race condition check)
          Optional<GameCharacter> existing = gameCharacterService.getCharacterIfExist(userIgn);
          if (existing.isPresent()) {
            log.info("✅ [AsyncWait] 캐릭터 이미 존재: {}", userIgn);
            return existing.get();
          }
          try {
            characterCreationListener.waitForCharacterCreation(userIgn).get();
          } catch (Exception e) {
            log.warn("⏳ [AsyncWait] 캐릭터 생성 대기 실패: {}", userIgn, e);
            throw new CharacterNotFoundException(userIgn);
          }

          // Fetch the created character
          Optional<GameCharacter> result = gameCharacterService.getCharacterIfExist(userIgn);
          if (result.isPresent()) {
            log.info("✅ [AsyncWait] 캐릭터 조회 완료: {}", userIgn);
            return result.get();
          }

          log.warn("⏳ [AsyncWait] 캐릭터 조회 타임아웃: {}", userIgn);
          throw new CharacterNotFoundException(userIgn);
        },
        context);
  }

  public GameCharacter findCharacterWithCache(String userIgn) {
    return findCharacterByUserIgn(userIgn);
  }
}

package maple.expectation.application.service.character;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

  private final GameCharacterService gameCharacterService;
  private final LogicExecutor executor;

  private static final int MAX_POLL_ATTEMPTS = 20;
  private static final long POLL_INTERVAL_MS = 500;

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
   * ADR-022: Redis MessageTopic/MessageQueue 제거 후 폴링 방식으로 변경
   *
   * <p>PostgreSQL 기반 구현체가 준비되면 PGMQ로 대체 예정
   */
  private GameCharacter waitForWorkerResult(String userIgn) {
    TaskContext context = TaskContext.of("CharacterFacade", "WaitWorker", userIgn);

    return executor.execute(
        () -> {
          log.info("📥 [Polling] 캐릭터 조회 대기 시작: {}", userIgn);

          for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Optional<GameCharacter> result = gameCharacterService.getCharacterIfExist(userIgn);
            if (result.isPresent()) {
              log.info("✅ [Polling] 캐릭터 조회 완료: {} (attempt: {})", userIgn, attempt + 1);
              return result.get();
            }

            try {
              TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new CharacterNotFoundException(userIgn);
            }
          }

          log.warn("⏳ [Polling] 캐릭터 조회 타임아웃: {}", userIgn);
          throw new CharacterNotFoundException(userIgn);
        },
        context);
  }

  public GameCharacter findCharacterWithCache(String userIgn) {
    return findCharacterByUserIgn(userIgn);
  }
}

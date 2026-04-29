package maple.expectation.application.service.character;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.character.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.character.notify.CharacterCreationListener;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

  private final GameCharacterService gameCharacterService;
  private final LogicExecutor executor;

  @Nullable @Autowired(required = false)
  private CharacterCreationListener characterCreationListener;

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
   * <p>Uses PostgreSQL LISTEN/NOTIFY via CharacterCreationListener. When a character is created,
   * CharacterCreationService sends NOTIFY and this method returns immediately via
   * CompletableFuture.
   *
   * <p>CF 체이닝으로 이벤트 대기 후 캐릭터를 조회합니다. findCharacterByUserIgn의 executor.execute 람다 내부에서 동기 경계로 인해
   * .join()이 사용됩니다.
   *
   * @see CharacterCreationNotifier
   * @see CharacterCreationListener
   */
  private GameCharacter waitForWorkerResult(String userIgn) {
    TaskContext context = TaskContext.of("CharacterFacade", "WaitWorker", userIgn);

    return executor.execute(() -> waitAndFetchCharacter(userIgn), context);
  }

  /** 캐릭터 생성 이벤트 대기 후 조회 (CF 체이닝, lambda 추출) */
  private GameCharacter waitAndFetchCharacter(String userIgn) {
    log.info("📥 [AsyncWait] 캐릭터 조회 대기 시작: {}", userIgn);

    Optional<GameCharacter> existing = gameCharacterService.getCharacterIfExist(userIgn);
    if (existing.isPresent()) {
      log.info("✅ [AsyncWait] 캐릭터 이미 존재: {}", userIgn);
      return existing.orElseThrow();
    }

    // waitForCharacterCreation이 null이면 (리스너 미설정, pgBouncer 환경 등) 바로 실패
    if (characterCreationListener == null) {
      throw new CharacterNotFoundException(userIgn);
    }
    CompletableFuture<GameCharacter> resultFuture =
        Optional.ofNullable(characterCreationListener.waitForCharacterCreation(userIgn))
            .orElseGet(
                () -> CompletableFuture.failedFuture(new CharacterNotFoundException(userIgn)))
            .thenCompose(ignored -> fetchCharacterAfterCreation(userIgn));

    // Sync boundary: executor.execute의 동기 람다 내부
    // join()은 CompletionException으로 래핑하므로 언래핑 필요
    // (LogicExecutor 내부 람다에서 예외 처리 — 기존 동작 유지)
    return unwrapJoinResult(resultFuture, userIgn);
  }

  /** CF join 후 CompletionException 언래핑 (lambda 추출) */
  private GameCharacter unwrapJoinResult(CompletableFuture<GameCharacter> future, String userIgn) {
    return executor.executeOrCatch(
        future::join,
        exception -> handleWaitFailure(userIgn, exception),
        TaskContext.of("CharacterFacade", "UnwrapCf", userIgn));
  }

  /** 캐릭터 생성 대기 실패 처리 */
  private GameCharacter handleWaitFailure(String userIgn, Throwable exception) {
    log.warn("⏳ [AsyncWait] 캐릭터 생성 대기 실패: {}", userIgn, exception);
    throw new CharacterNotFoundException(userIgn);
  }

  /** 생성 완료 후 캐릭터 조회 */
  private CompletableFuture<GameCharacter> fetchCharacterAfterCreation(String userIgn) {
    Optional<GameCharacter> result = gameCharacterService.getCharacterIfExist(userIgn);
    if (result.isPresent()) {
      log.info("✅ [AsyncWait] 캐릭터 조회 완료: {}", userIgn);
      return CompletableFuture.completedFuture(result.orElseThrow());
    }
    log.warn("⏳ [AsyncWait] 캐릭터 조회 타임아웃: {}", userIgn);
    return CompletableFuture.failedFuture(new CharacterNotFoundException(userIgn));
  }

  public GameCharacter findCharacterWithCache(String userIgn) {
    return findCharacterByUserIgn(userIgn);
  }
}

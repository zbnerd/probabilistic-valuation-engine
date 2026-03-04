package maple.expectation.application.service.character;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.MessageQueue;
import maple.expectation.core.port.out.MessageTopic;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.util.AsyncUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

  private final GameCharacterService gameCharacterService;
  private final MessageTopic<String> characterEventTopic;
  private final MessageQueue<String> characterJobQueue;
  private final LogicExecutor executor;

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

  private GameCharacter waitForWorkerResult(String userIgn) {
    CompletableFuture<GameCharacter> future = new CompletableFuture<>();
    TaskContext context = TaskContext.of("CharacterFacade", "WaitWorker", userIgn);

    int listenerId =
        characterEventTopic.addListener(
            String.class,
            (channel, msg) -> {
              if ("DONE".equals(msg)) {
                gameCharacterService.getCharacterIfExist(userIgn).ifPresent(future::complete);
              } else if ("NOT_FOUND".equals(msg)) {
                future.completeExceptionally(new CharacterNotFoundException(userIgn));
              }
            });

    return executor.executeWithFinally(
        () -> {
          performQueueOffer(userIgn);
          // ✅ TaskContext를 전달하여 하위 메서드에서 예외 번역 시 활용
          return awaitFuture(future, userIgn, context);
        },
        () -> characterEventTopic.removeListener(listenerId),
        context);
  }

  private void performQueueOffer(String userIgn) {
    characterJobQueue.offer(userIgn);
    log.info("📥 [Queue Enqueue] 작업 등록: {}", userIgn);
  }

  /**
   * Issue #169: TimeoutException 전파 수정
   *
   * <h4>5-Agent Council Round 2 결정</h4>
   *
   * <p>TimeoutException을 CompletionException으로 래핑하여 GlobalExceptionHandler가 처리
   *
   * <ul>
   *   <li><b>변경 전</b>: TimeoutException → ExternalServiceException (cause 누락, 서킷브레이커 오동작)
   *   <li><b>변경 후</b>: TimeoutException → CompletionException (GlobalExceptionHandler → 503 +
   *       Retry-After)
   * </ul>
   *
   * <h4>Purple Agent 피드백 반영</h4>
   *
   * <ul>
   *   <li>Exception chain 보존 (cause 정보 유지)
   *   <li>cause null 체크 추가 (방어적 코딩)
   *   <li>InterruptedException 처리 + 인터럽트 플래그 복원
   * </ul>
   */
  private GameCharacter awaitFuture(
      CompletableFuture<GameCharacter> future, String userIgn, TaskContext context) {
    return executor.executeWithTranslation(
        // 🚀 1. 작업: Checked Exception을 그대로 던지게 둡니다.
        () -> future.get(10, TimeUnit.SECONDS),

        // 🚀 2. 번역: 발생한 Throwable을 여기서 요리합니다.
        (e, ctx) -> {
          // 비동기 실행 중 발생한 실제 원인(cause)을 추출합니다.
          Throwable cause = AsyncUtils.unwrapCompletionException(e);

          // 이미 도메인 예외(404 등)라면 그대로 던집니다.
          if (cause instanceof CharacterNotFoundException cnfe) {
            return cnfe;
          }

          // Issue #169: TimeoutException → CompletionException으로 래핑하여 GlobalExceptionHandler가 처리
          // GlobalExceptionHandler.handleCompletionException()에서 503 + Retry-After 30초 응답
          if (cause instanceof TimeoutException te) {
            log.warn("⏳ [Timeout] 캐릭터 생성 대기 실패 (IGN: {}): {}", userIgn, te.getMessage());
            throw new CompletionException(te);
          }

          // InterruptedException 처리: 인터럽트 플래그 복원 후 CompletionException으로 래핑
          if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("⏳ [Interrupted] 캐릭터 생성 대기 중단 (IGN: {})", userIgn);
            throw new CompletionException(ie);
          }

          // 그 외 예상치 못한 예외: InternalSystemException으로 변환 (cause chain 보존)
          log.error("⏳ [Error] 캐릭터 생성 대기 실패 (IGN: {}): {}", userIgn, cause.getMessage());
          return new InternalSystemException("CharacterFacade:WaitWorker:" + userIgn, cause);
        },
        context);
  }

  public GameCharacter findCharacterWithCache(String userIgn) {
    return findCharacterByUserIgn(userIgn);
  }
}

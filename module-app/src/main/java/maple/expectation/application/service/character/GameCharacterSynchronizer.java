package maple.expectation.application.service.character;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.error.exception.CharacterNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v2.GameCharacterService;
import org.redisson.api.RCountDownLatch;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameCharacterSynchronizer {

  private final GameCharacterService gameCharacterService;
  private final org.redisson.api.RedissonClient redissonClient;
  private final LogicExecutor executor; // ✅ 지능형 실행 엔진 주입

  public GameCharacter synchronizeCharacter(String userIgn) {
    String cleanUserIgn = userIgn.trim();
    TaskContext context = TaskContext.of("Synchronizer", "SyncCharacter", cleanUserIgn); //

    return executor.execute(
        () -> {
          // 1. Negative Cache 사전 차단
          if (gameCharacterService.isNonExistent(cleanUserIgn)) {
            throw new CharacterNotFoundException(cleanUserIgn);
          }

          RCountDownLatch latch = redissonClient.getCountDownLatch("latch:char:" + cleanUserIgn);

          // 2. 리더 권한 획득 시도
          boolean isLeader = latch.trySetCount(1);

          if (isLeader) {
            return performLeaderTask(latch, cleanUserIgn, context);
          } else {
            return performFollowerTask(latch, cleanUserIgn, context);
          }
        },
        context); //
  }

  /** ✅ 리더 로직: try-finally를 executeWithFinally로 평탄화 */
  private GameCharacter performLeaderTask(
      RCountDownLatch latch, String userIgn, TaskContext context) {
    return executor.executeWithFinally(
        () -> {
          log.info("👑 [Leader] 신규 생성 주도: {}", userIgn);
          return gameCharacterService.createNewCharacter(userIgn);
        },
        () -> {
          // 🚀 [Cleanup] 작업 완료 후 모든 대기자에게 알림 및 래치 삭제
          latch.countDown();
          latch.delete();
        },
        context);
  }

  /** ✅ 팔로워 로직: InterruptedException 처리를 executeWithTranslation으로 위임 */
  private GameCharacter performFollowerTask(
      RCountDownLatch latch, String userIgn, TaskContext context) {
    return executor.executeWithTranslation(
        () -> {
          log.info("😴 [Follower] 대장을 기다림: {}", userIgn);

          // 대기 로직 실행
          boolean completed = latch.await(5, TimeUnit.SECONDS);

          if (!completed) {
            log.warn("⏰ [Follower Timeout] 대장이 너무 느려 직접 DB 확인: {}", userIgn);
          }

          return gameCharacterService
              .getCharacterIfExist(userIgn)
              .orElseThrow(() -> new CharacterNotFoundException(userIgn));
        },
        ExceptionTranslator.forLock(), // InterruptedException 발생 시 스레드 중단 상태를 복구하고 예외 변환
        context);
  }
}

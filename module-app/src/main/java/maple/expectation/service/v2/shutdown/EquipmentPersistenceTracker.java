package maple.expectation.service.v2.shutdown;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Equipment 비동기 저장 작업 추적기 (최종 평탄화 완료)
 *
 * <h3>#271 V5 Stateless Architecture</h3>
 *
 * <p>PersistenceTrackerStrategy 인터페이스 구현체 (In-Memory 모드)
 *
 * <h3>Issue #283 P1-15: Scale-out 분산 안전성</h3>
 *
 * <p>이 구현체는 {@code @ConditionalOnProperty(name = "app.buffer.redis.enabled", havingValue =
 * "false")}로 In-Memory 모드에서만 활성화됩니다. Scale-out 환경에서는 Redis 구현체 ({@code
 * RedisEquipmentPersistenceTracker})가 대신 로드됩니다.
 *
 * <ul>
 *   <li>{@code shutdownInProgress}: AtomicBoolean - 인스턴스 로컬 shutdown 상태. 각 인스턴스가 독립적으로 자신의 비동기 작업
 *       완료를 대기하므로 분산화 불필요.
 *   <li>{@code pendingOperations}: ConcurrentHashMap - 이 인스턴스에서 시작된 비동기 작업만 추적. CompletableFuture는
 *       본질적으로 로컬이므로 분산화 불가.
 * </ul>
 *
 * <p><b>결론: 이미 Strategy 패턴으로 In-Memory/Redis 분리 완료. 추가 변환 불필요.</b>
 *
 * @see PersistenceTrackerStrategy 전략 인터페이스
 * @see maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker Redis
 *     구현체
 */
@Slf4j
@ConditionalOnProperty(name = "app.buffer.redis.enabled", havingValue = "false")
@Component
@RequiredArgsConstructor
public class EquipmentPersistenceTracker implements PersistenceTrackerStrategy {

  private final LogicExecutor executor;
  private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingOperations =
      new ConcurrentHashMap<>();

  // P1-9 Fix: CLAUDE.md Section 23 - volatile → AtomicBoolean (CAS 연산으로 race condition 방지)
  private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);

  @Override
  public void trackOperation(String ocid, CompletableFuture<Void> future) {
    // P1-9 Fix: AtomicBoolean.get()으로 thread-safe 읽기
    if (shutdownInProgress.get()) {
      log.warn("⚠️ [Persistence] Shutdown 진행 중 - 작업 거부: {}", ocid);
      throw new IllegalStateException("Shutdown 진행 중에는 등록할 수 없습니다.");
    }

    pendingOperations.put(ocid, future);

    future.whenComplete(
        (result, throwable) ->
            executor.executeVoidJava(
                () -> {
                  pendingOperations.remove(ocid);
                  if (throwable != null) {
                    log.error("❌ [Persistence] 비동기 저장 실패: {}", ocid);
                    return;
                  }
                  log.debug("✅ [Persistence] 비동기 저장 완료: {}", ocid);
                },
                TaskContext.of("Persistence", "CompleteOperation", ocid)));
  }

  /**
   * ✅ [최종 박멸] 새로운 패턴(executeWithFallback)을 적용한 클린 코드 try-catch도, throws Throwable도 없는 순수 비즈니스
   * 로직입니다.
   */
  @Override
  public boolean awaitAllCompletion(Duration timeout) {
    // P1-9 Fix: CAS 연산으로 shutdown 상태 원자적 전환
    if (!shutdownInProgress.compareAndSet(false, true)) {
      log.warn("⚠️ [Persistence] Shutdown 이미 진행 중");
      return false;
    }
    log.info("🚫 [Persistence] Shutdown 시작 - 새로운 작업 등록 차단");

    if (pendingOperations.isEmpty()) return true;

    TaskContext context =
        TaskContext.of("Persistence", "AwaitAll", String.valueOf(pendingOperations.size()));

    // 🚀 [패턴 8] 적용: 체크 예외를 엔진 내부에서 처리하여 호출부를 해방시킵니다.
    return executor.executeWithFallback(
        () -> {
          log.info(
              "⏳ [Persistence] {}건 작업 대기 중... (timeout: {}s)",
              pendingOperations.size(),
              timeout.getSeconds());

          // CompletableFuture.get()은 TimeoutException(Checked)을 던지지만 executor가 잡아줍니다.
          CompletableFuture.allOf(pendingOperations.values().toArray(new CompletableFuture[0]))
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

          log.info("✅ [Persistence] 모든 작업 완료");
          return true;
        },
        (e) -> {
          // 예외 타입에 따른 사후 처리 시나리오만 집중해서 작성합니다.
          if (e instanceof java.util.concurrent.TimeoutException) {
            log.warn("⏱️ [Persistence] Timeout 발생. 미완료 작업: {}건", pendingOperations.size());
          } else {
            log.error("❌ [Persistence] 작업 대기 중 예외 발생: {}", e.getMessage());
          }
          return false; // Fallback 결과값 반환
        },
        context);
  }

  @Override
  public List<String> getPendingOcids() {
    return new ArrayList<>(pendingOperations.keySet());
  }

  @Override
  public int getPendingCount() {
    return pendingOperations.size();
  }

  @Override
  public void resetForTesting() {
    // P1-9 Fix: AtomicBoolean.set()으로 리셋
    shutdownInProgress.set(false);
    pendingOperations.clear();
    log.debug("🔄 [Persistence] 테스트용 리셋 완료");
  }

  @Override
  public StrategyType getType() {
    return StrategyType.IN_MEMORY;
  }
}

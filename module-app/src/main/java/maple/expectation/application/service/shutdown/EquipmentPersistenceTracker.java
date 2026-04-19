package maple.expectation.application.service.shutdown;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.PersistenceTrackerPort;
import maple.expectation.core.port.out.PersistenceTrackerStrategy;
import maple.expectation.core.port.out.PersistenceTrackerStrategy.StrategyType;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Equipment 비동기 저장 작업 추적기 (PostgreSQL 백업)
 *
 * <h3>Issue #633: In-Memory → PostgreSQL</h3>
 *
 * <p>PersistenceTrackerStrategy 인터페이스 구현체 (PostgreSQL 모드). 비동기 작업을 PostgreSQL regular table에 기록하여
 * 인스턴스 장애 시 복구 가능.
 *
 * <ul>
 *   <li>{@code shutdownInProgress}: AtomicBoolean - 인스턴스 로컬 shutdown 상태.
 *   <li>{@code pendingOperations}: ConcurrentHashMap - 이 인스턴스에서 시작된 비동기 작업만 추적.
 *   <li>{@code port}: PersistenceTrackerPort - PostgreSQL 어댑터 (Port/Adapter 패턴).
 * </ul>
 *
 * @see PersistenceTrackerStrategy 전략 인터페이스
 * @see PersistenceTrackerPort Port 인터페이스
 * @see maple.expectation.infrastructure.persistence.PostgresPersistenceTrackerAdapter PostgreSQL
 *     어댑터
 */
@Slf4j
@Component
public class EquipmentPersistenceTracker implements PersistenceTrackerStrategy {

  private final LogicExecutor executor;
  private final PersistenceTrackerPort port;
  private final String instanceId;
  private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingOperations =
      new ConcurrentHashMap<>();

  // P1-9 Fix: CLAUDE.md Section 23 - volatile → AtomicBoolean (CAS 연산으로 race condition 방지)
  private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);

  public EquipmentPersistenceTracker(
      LogicExecutor executor,
      PersistenceTrackerPort port,
      @Value("${app.instance-id:${HOSTNAME:unknown}}") String instanceId) {
    this.executor = executor;
    this.port = port;
    this.instanceId = instanceId;
  }

  @Override
  public void trackOperation(String ocid, CompletableFuture<Void> future) {
    // P1-9 Fix: AtomicBoolean.get()으로 thread-safe 읽기
    if (shutdownInProgress.get()) {
      log.warn("[Persistence] Shutdown in progress - rejecting: {}", ocid);
      throw new IllegalStateException("Shutdown in progress");
    }

    port.insertPending(ocid, instanceId);
    pendingOperations.put(ocid, future);

    future.whenComplete(
        (result, throwable) ->
            executor.executeVoidJava(
                () -> {
                  port.markCompleted(ocid);
                  pendingOperations.remove(ocid);
                  if (throwable != null) {
                    log.error("[Persistence] Async save failed: {}", ocid);
                    return;
                  }
                  log.debug("[Persistence] Async save completed: {}", ocid);
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
    return StrategyType.POSTGRES;
  }

  @PostConstruct
  void recoverPendingOperations() {
    List<String> pending = port.findPendingOperations();
    if (pending.isEmpty()) return;
    log.warn(
        "[Persistence] Found {} pending operations from previous run: {}", pending.size(), pending);
    pending.forEach(ocid -> log.warn("[Persistence] Unrecovered pending: ocid={}", ocid));
  }
}

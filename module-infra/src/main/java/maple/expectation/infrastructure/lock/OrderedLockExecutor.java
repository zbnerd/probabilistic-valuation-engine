package maple.expectation.infrastructure.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * 순서 보장 다중 락 실행기 (Issue #221: N02-Lock Ordering Deadlock)
 *
 * <h3>목적</h3>
 *
 * <p>Coffman Condition #4 (Circular Wait)를 방지하여 Deadlock을 예방합니다. 락 키를 알파벳순으로 정렬하여 모든 스레드가 동일한 순서로
 * 락을 획득합니다.
 *
 * <h3>5-Agent Council 피드백 반영</h3>
 *
 * <ul>
 *   <li>🔵 Blue Agent: 순차 획득 방식으로 진정한 Deadlock Prevention
 *   <li>🟢 Green Agent: 반복 패턴 사용 (재귀 대신), System.nanoTime() 정밀도
 *   <li>🔴 Red Agent: deadline 기반 타임아웃으로 30초 상한 보장 (P0-RED-01)
 * </ul>
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 12: Zero Try-Catch Policy - LogicExecutor 패턴 사용
 *   <li>Section 6: 생성자 주입 (@RequiredArgsConstructor)
 * </ul>
 *
 * <h3>사용 예시</h3>
 *
 * <pre>{@code
 * // 계좌 이체: A -> B 순서 보장 (알파벳순 정렬로 Deadlock 방지)
 * orderedLockExecutor.executeWithOrderedLocks(
 *     List.of("account:user1", "account:user2"),
 *     30, TimeUnit.SECONDS, 60,
 *     () -> transferService.transfer(user1, user2, amount)
 * );
 * }</pre>
 *
 * @see LockStrategy
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderedLockExecutor {

  private static final int MAX_NESTED_DEPTH = 10; // P1-YELLOW-01: 스택 깊이 제한

  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;

  // P1-BLUE-01: 전략 캐싱 (Lock-Free 초기화) - CLAUDE.md Section 14 준수
  private final AtomicReference<Boolean> nestedStrategyRequired = new AtomicReference<>();

  /**
   * 순서 보장 다중 락 실행 (반복 패턴)
   *
   * <p><b>Green Agent 권장사항 반영</b>:
   *
   * <ul>
   *   <li>재귀 대신 반복 패턴으로 스택 오버플로우 방지
   *   <li>System.nanoTime()으로 정밀한 타임아웃 계산
   *   <li>단일 finally 블록에서 LIFO 순서 락 해제
   * </ul>
   *
   * @param keys 락 키 목록 (내부에서 알파벳순 정렬됨)
   * @param totalTimeout 전체 타임아웃 값
   * @param timeUnit 타임아웃 단위
   * @param leaseTime 각 락의 유지 시간 (초)
   * @param task 실행할 작업
   * @return 작업 결과
   */
  public <T> T executeWithOrderedLocks(
      List<String> keys,
      long totalTimeout,
      TimeUnit timeUnit,
      long leaseTime,
      ThrowingSupplier<T> task) {
    TaskContext context = TaskContext.of("OrderedLock", "Execute", String.join(",", keys));

    return executor.execute(
        () -> executeWithOrderedLocksInternal(keys, totalTimeout, timeUnit, leaseTime, task),
        context);
  }

  /**
   * 내부 구현: 반복 패턴 또는 중첩 콜백으로 락 획득 및 실행
   *
   * <p><b>PR #236 Fix: MySQL Named Lock 지원</b>
   *
   * <ul>
   *   <li>Redisson: tryLockImmediately + 반복 패턴 (기존)
   *   <li>MySQL: 중첩 콜백 전략 (세션 유지)
   * </ul>
   *
   * <p><b>알고리즘</b>:
   *
   * <ol>
   *   <li>키를 알파벳순 정렬 (Circular Wait 조건 제거)
   *   <li>LockStrategy 타입 감지
   *   <li>MySQL: 중첩 콜백 전략 실행
   *   <li>Redisson: 반복 패턴 실행
   * </ol>
   */
  private <T> T executeWithOrderedLocksInternal(
      List<String> keys,
      long totalTimeout,
      TimeUnit timeUnit,
      long leaseTime,
      ThrowingSupplier<T> task)
      throws Throwable {
    // 1. 정렬하여 Circular Wait 조건 제거
    List<String> sortedKeys = keys.stream().sorted().toList();

    log.debug("[OrderedLock] Acquiring {} locks in order: {}", sortedKeys.size(), sortedKeys);

    // 2. PR #236: MySQL Named Lock 감지 → 중첩 콜백 전략 사용
    if (requiresNestedStrategy()) {
      log.debug("[OrderedLock] Using nested callback strategy (MySQL Named Lock detected)");
      return executeWithNestedLocks(
          sortedKeys, 0, timeUnit.toMillis(totalTimeout), leaseTime, task);
    }

    // 3. Redisson: 기존 반복 패턴 사용
    return executeWithIterativeStrategy(sortedKeys, totalTimeout, timeUnit, leaseTime, task);
  }

  /** Redisson용 반복 패턴 전략 (CLAUDE.md Section 12 준수: executeWithFinally 패턴) */
  private <T> T executeWithIterativeStrategy(
      List<String> sortedKeys,
      long totalTimeout,
      TimeUnit timeUnit,
      long leaseTime,
      ThrowingSupplier<T> task)
      throws Throwable {
    // [P0-RED-01] deadline 계산 (나노초 정밀도)
    long deadlineNanos = System.nanoTime() + timeUnit.toNanos(totalTimeout);

    // 획득한 락 추적 (LIFO 해제용) - final 참조로 람다에서 접근 가능
    final List<String> acquiredLocks = new ArrayList<>(sortedKeys.size());

    TaskContext context =
        TaskContext.of("OrderedLock", "IterativeStrategy", String.join(",", sortedKeys));

    return executor.executeWithFinally(
        () -> acquireLocksAndExecute(sortedKeys, deadlineNanos, leaseTime, task, acquiredLocks),
        () -> releaseLocksInReverseOrder(acquiredLocks),
        context);
  }

  /** 락 순차 획득 후 작업 실행 (CLAUDE.md Section 15 준수: 메서드 추출) */
  private <T> T acquireLocksAndExecute(
      List<String> sortedKeys,
      long deadlineNanos,
      long leaseTime,
      ThrowingSupplier<T> task,
      List<String> acquiredLocks)
      throws Throwable {
    for (int i = 0; i < sortedKeys.size(); i++) {
      String currentKey = sortedKeys.get(i);

      // [P0-RED-01] 남은 시간 계산
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new DistributedLockException(
            String.format("전체 락 타임아웃 초과: %d/%d 락 획득 중 [key=%s]", i, sortedKeys.size(), currentKey));
      }

      // 남은 시간을 waitTime으로 변환 (최소 1초, 최대 10초)
      long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
      long waitTimeSec = Math.max(1, Math.min(remainingSeconds, 10));

      log.debug(
          "[OrderedLock] Acquiring lock {}/{}: {} (remaining: {}ms)",
          i + 1,
          sortedKeys.size(),
          currentKey,
          TimeUnit.NANOSECONDS.toMillis(remainingNanos));

      // 락 획득 시도
      boolean acquired = tryAcquireLock(currentKey, waitTimeSec, leaseTime);
      if (!acquired) {
        throw new DistributedLockException(
            String.format("락 획득 실패: %s (waited %ds)", currentKey, waitTimeSec));
      }

      acquiredLocks.add(currentKey);
    }

    log.info("[OrderedLock] All {} locks acquired, executing task", sortedKeys.size());

    // 작업 실행
    return task.get();
  }

  /**
   * 락 획득 시도 (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
   *
   * <p>tryLockImmediately를 먼저 시도하고, 지원하지 않으면 중첩 콜백 전략 플래그 설정
   *
   * @return true: 락 획득 성공 (Redisson), false: 중첩 전략 필요 (MySQL) 또는 실패
   */
  private boolean tryAcquireLock(String key, long waitTimeSec, long leaseTime) {
    TaskContext context = TaskContext.of("OrderedLock", "TryAcquire", key);

    return executor.executeOrDefault(
        () -> lockStrategy.tryLockImmediately(key, leaseTime),
        false, // UnsupportedOperationException 또는 기타 예외 시 false 반환 → 중첩 전략으로 전환
        context);
  }

  /**
   * PR #236 Fix: MySQL Named Lock용 중첩 콜백 전략
   *
   * <h4>문제</h4>
   *
   * <p>{@code executeWithLock(() -> null)}이 즉시 락 해제 → 보호 없음
   *
   * <h4>해결</h4>
   *
   * <p>MySQL 감지 시 모든 락을 중첩 콜백으로 감싸서 세션 유지
   *
   * @param sortedKeys 정렬된 락 키 목록
   * @param currentIndex 현재 처리 중인 인덱스
   * @param remainingTimeoutMs 남은 타임아웃 (ms)
   * @param leaseTime 락 유지 시간 (초)
   * @param task 실행할 작업
   * @return 작업 결과
   */
  private <T> T executeWithNestedLocks(
      List<String> sortedKeys,
      int currentIndex,
      long remainingTimeoutMs,
      long leaseTime,
      ThrowingSupplier<T> task)
      throws Throwable {
    // P1-YELLOW-01: 스택 깊이 제한
    if (currentIndex >= MAX_NESTED_DEPTH) {
      throw new DistributedLockException(
          String.format("중첩 락 깊이 초과: 최대 %d개까지 지원 (요청: %d개)", MAX_NESTED_DEPTH, sortedKeys.size()));
    }

    // Base case: 모든 락 획득 완료 → 작업 실행
    if (currentIndex >= sortedKeys.size()) {
      log.info("[OrderedLock/Nested] All {} locks acquired, executing task", sortedKeys.size());
      return task.get();
    }

    String currentKey = sortedKeys.get(currentIndex);
    long waitTimeSec = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingTimeoutMs));

    log.debug(
        "[OrderedLock/Nested] Acquiring lock {}/{}: {} (remaining: {}ms)",
        currentIndex + 1,
        sortedKeys.size(),
        currentKey,
        remainingTimeoutMs);

    // 중첩 콜백: 현재 락 안에서 다음 락 획득
    return lockStrategy.executeWithLock(
        currentKey,
        waitTimeSec,
        leaseTime,
        () ->
            executeWithNestedLocks(
                sortedKeys,
                currentIndex + 1,
                remainingTimeoutMs - TimeUnit.SECONDS.toMillis(waitTimeSec),
                leaseTime,
                task));
  }

  /**
   * P1-BLUE-01 Fix: MySQL Named Lock 지원 여부 확인 (결과 캐싱)
   *
   * <p>CLAUDE.md Section 14 준수: synchronized 대신 AtomicReference.compareAndSet() 사용
   *
   * <p>Lock-Free CAS 기반 Lazy 초기화로 동시성 문제 해결
   */
  private boolean requiresNestedStrategy() {
    Boolean cached = nestedStrategyRequired.get();
    if (cached != null) {
      return cached;
    }

    // Lock-Free CAS: 최초 한 번만 probe 실행
    Boolean detected = detectNestedStrategyRequired();
    if (nestedStrategyRequired.compareAndSet(null, detected)) {
      log.info("[OrderedLock] Strategy detection: nestedRequired={}", detected);
      return detected;
    }

    // CAS 실패 시 다른 스레드가 이미 설정한 값 반환
    return nestedStrategyRequired.get();
  }

  /**
   * 전략 감지 (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
   *
   * <p>tryLockImmediately 지원 여부를 probe하여 MySQL vs Redisson 구분
   *
   * @return true: MySQL (중첩 전략 필요), false: Redisson (일반 전략)
   */
  private boolean detectNestedStrategyRequired() {
    TaskContext context = TaskContext.of("OrderedLock", "StrategyProbe", "__probe__:strategy__");

    // executeOrDefault: 예외 발생 시 true 반환 → MySQL 중첩 전략 사용
    return executor.executeOrDefault(
        () -> {
          lockStrategy.tryLockImmediately("__probe__:strategy__", 1);
          unlockSafely("__probe__:strategy__");
          return false; // Redisson: 일반 전략 사용
        },
        true, // UnsupportedOperationException 또는 기타 예외 → MySQL 중첩 전략
        context);
  }

  /**
   * [Green Agent] LIFO 순서로 락 해제 (CLAUDE.md Section 12 준수)
   *
   * <p>역순으로 해제하여 데드락 가능성 최소화
   */
  private void releaseLocksInReverseOrder(List<String> acquiredLocks) {
    for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
      String lockKey = acquiredLocks.get(i);
      unlockSafely(lockKey);
    }
  }

  /**
   * 안전한 락 해제 (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
   *
   * <p>락 해제 실패 시 로그만 남기고 계속 진행 (Fail-Safe)
   */
  private void unlockSafely(String lockKey) {
    TaskContext context = TaskContext.of("OrderedLock", "Unlock", lockKey);

    executor.executeVoidJava(
        () -> {
          lockStrategy.unlock(lockKey);
          log.debug("[OrderedLock] Released lock: {}", lockKey);
        },
        context);
  }

  /** 편의 메서드: 초 단위 타임아웃 */
  public <T> T executeWithOrderedLocks(
      List<String> keys, long totalTimeoutSeconds, long leaseTime, ThrowingSupplier<T> task) {
    return executeWithOrderedLocks(keys, totalTimeoutSeconds, TimeUnit.SECONDS, leaseTime, task);
  }
}

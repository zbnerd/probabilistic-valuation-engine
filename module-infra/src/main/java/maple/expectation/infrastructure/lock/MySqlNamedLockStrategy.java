package maple.expectation.infrastructure.lock;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.error.exception.DatabaseNamedLockException;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * MySQL Named Lock 전략
 *
 * <h3>P0-N09 Fix: Lock Ordering 추적 (Issue #228)</h3>
 *
 * <p>ThreadLocal을 사용하여 현재 스레드가 보유한 락의 획득 순서를 추적합니다. 알파벳순으로 앞선 락을 나중에 획득하려 하면 WARN 로그와 메트릭을 기록합니다.
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 12: Zero Try-Catch Policy - LogicExecutor 패턴 사용
 *   <li>P0-BLUE-01: ThreadLocal.remove() 반드시 호출 (메모리 누수 방지)
 *   <li>P1-BLUE-03: LockOrderMetrics 의존성 주입
 *   <li>Issue #310: LockMetrics 의존성 주입 (락 대기 시간, 실패율, 활성 락 수)
 * </ul>
 *
 * <h3>Conditional Bean Loading</h3>
 *
 * <p>이 빈은 lockJdbcTemplate이 존재할 때만 로드됩니다. LockHikariConfig는 @Profile({"!test", "container"})이므로,
 * test 프로필만 활성화된 환경에서는 이 빈이 생성되지 않습니다. ResilientLockStrategy는 Optional로 주입받아 Redis-only 모드로 동작합니다.
 *
 * @see LockOrderMetrics
 * @see LockMetrics
 */
@Slf4j
@Component
@ConditionalOnBean(name = "lockJdbcTemplate")
public class MySqlNamedLockStrategy implements LockStrategy {

  private final JdbcTemplate lockJdbcTemplate;
  private final LogicExecutor executor;
  private final LockOrderMetrics lockOrderMetrics;
  private final LockMetrics lockMetrics;

  /**
   * [P0-BLUE-01] ThreadLocal 메모리 누수 방지
   *
   * <p>스레드 풀 재사용 환경(Virtual Threads 포함)에서 메모리 누수를 방지하기 위해 반드시 finally 블록에서 cleanupLockTracking()을
   * 호출해야 합니다.
   *
   * <p>Deque 사용 이유: LIFO 순서로 락 해제 추적 용이
   *
   * <h4>V5 Stateless Architecture 검증 완료 (#271)</h4>
   *
   * <ul>
   *   <li>용도: Lock Ordering 추적 (Deadlock 방지)
   *   <li>범위: 요청 내 일시적 상태, cross-request 상태 아님
   *   <li>정리: cleanupLockTracking()에서 빈 Deque면 remove() 호출
   *   <li>MDC 전환 불필요: 락 특화 로깅 이미 존재, 고빈도 작업
   * </ul>
   */
  private static final ThreadLocal<Deque<String>> ACQUIRED_LOCKS =
      ThreadLocal.withInitial(ArrayDeque::new);

  public MySqlNamedLockStrategy(
      @Qualifier("lockJdbcTemplate") JdbcTemplate lockJdbcTemplate,
      LogicExecutor executor,
      LockOrderMetrics lockOrderMetrics,
      LockMetrics lockMetrics) {
    this.lockJdbcTemplate = lockJdbcTemplate;
    this.executor = executor;
    this.lockOrderMetrics = lockOrderMetrics;
    this.lockMetrics = lockMetrics;
  }

  @Override
  public <T> T executeWithLock(
      String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
    String lockKey = buildLockKey(key);
    TaskContext context = TaskContext.of("Lock", "MySqlExecute", key);

    // [패턴 6] 최상단에서 모든 예외를 도메인 예외로 세탁
    return executor.executeWithTranslation(
        () -> this.executeInSession(lockKey, waitTime, task, context),
        ExceptionTranslator.forLock(),
        context);
  }

  @Override
  public <T> T executeWithLock(String key, ThrowingSupplier<T> task) {
    return executeWithLock(key, 10, 20, task);
  }

  /** 평탄화의 핵심: 람다 중첩과 try-catch를 메서드 추출로 해결 */
  private <T> T executeInSession(
      String lockKey, long waitTime, ThrowingSupplier<T> task, TaskContext context) {
    // 1. 명시적 캐스팅으로 람다 모호성 해결 (괄호 한 번만 열림)
    return lockJdbcTemplate.execute(
        (ConnectionCallback<T>)
            conn -> this.runLogicWithPinnedSession(conn, lockKey, waitTime, task, context));
  }

  /** P0: 세션 고정 환경에서 로직 실행 (패턴 1 활용) 이 메서드는 체크 예외를 던지지 않으므로 콜백 내부에서 안전하게 실행됩니다. */
  private <T> T runLogicWithPinnedSession(
      Connection conn,
      String lockKey,
      long waitTime,
      ThrowingSupplier<T> task,
      TaskContext context) {
    JdbcTemplate sessionJdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));

    // [패턴 1] try-finally 키워드 대신 executeWithFinally 사용
    // [P0-BLUE-01] ThreadLocal 정리를 finally에서 수행
    return executor.executeWithFinally(
        () -> this.acquireAndExecute(sessionJdbc, lockKey, waitTime, task),
        () -> this.releaseAndCleanup(sessionJdbc, lockKey, context),
        context);
  }

  /**
   * 락 획득 및 작업 실행
   *
   * <p>[P0-N09] Lock Ordering 검증 후 락 획득
   */
  private <T> T acquireAndExecute(
      JdbcTemplate sessionJdbc, String lockKey, long waitTime, ThrowingSupplier<T> task)
      throws Throwable {
    // 1. Lock Ordering 검증 (Deadlock 위험 감지)
    validateLockOrder(lockKey);

    // 2. 락 획득 시도
    if (!tryAcquire(sessionJdbc, lockKey, waitTime)) {
      throw new DistributedLockException("락 획득 타임아웃: " + lockKey);
    }

    // 3. 락 획득 성공 - 추적 시작
    trackLockAcquisition(lockKey);
    log.info("🔓 [MySQL Lock] '{}' 획득 성공", lockKey);

    // 4. 작업 실행
    return task.get();
  }

  /**
   * [P0-N09] 락 순서 검증
   *
   * <p>Coffman Condition #4 (Circular Wait) 위반 감지: 현재 보유 중인 락보다 알파벳순으로 앞선 락을 획득하려 하면 경고
   *
   * @param lockKey 획득하려는 락 키
   */
  private void validateLockOrder(String lockKey) {
    Deque<String> acquired = ACQUIRED_LOCKS.get();
    if (!acquired.isEmpty()) {
      String lastLock = acquired.peekLast();
      // 알파벳순으로 현재 락이 마지막 락보다 앞서면 위반
      if (lockKey.compareTo(lastLock) < 0) {
        // 메트릭 기록 (경고 로그는 LockOrderMetrics에서 처리)
        lockOrderMetrics.recordViolation(lockKey, lastLock);
      }
    }
  }

  /**
   * 락 획득 추적
   *
   * @param lockKey 획득한 락 키
   */
  private void trackLockAcquisition(String lockKey) {
    ACQUIRED_LOCKS.get().addLast(lockKey);
    lockOrderMetrics.recordAcquisition(lockKey);
  }

  /**
   * [P0-BLUE-01] 락 해제 및 ThreadLocal 정리
   *
   * <p>메모리 누수 방지를 위해 반드시 finally에서 호출되어야 합니다.
   */
  private void releaseAndCleanup(JdbcTemplate sessionJdbc, String lockKey, TaskContext context) {
    // 1. 락 해제
    releaseLock(sessionJdbc, lockKey, context);

    // 2. ThreadLocal 정리 (메모리 누수 방지)
    cleanupLockTracking(lockKey);
  }

  /**
   * [P0-BLUE-01] 락 추적 정리 - 메모리 누수 방지 핵심
   *
   * <p>스레드 풀 환경(Virtual Threads 포함)에서 ThreadLocal 누수를 방지합니다. 빈 Deque면 ThreadLocal을 완전히 제거합니다.
   */
  private void cleanupLockTracking(String lockKey) {
    Deque<String> acquired = ACQUIRED_LOCKS.get();
    acquired.removeLastOccurrence(lockKey);
    lockOrderMetrics.recordRelease(lockKey);

    // 빈 경우 ThreadLocal 완전 제거 (Critical: 메모리 누수 방지)
    if (acquired.isEmpty()) {
      ACQUIRED_LOCKS.remove();
    }
  }

  private boolean tryAcquire(JdbcTemplate sessionJdbc, String lockKey, long waitTime) {
    Integer acquiredFlag =
        sessionJdbc.queryForObject("SELECT GET_LOCK(?, ?)", Integer.class, lockKey, waitTime);

    if (acquiredFlag == null) {
      throw new DatabaseNamedLockException("GET_LOCK", lockKey, waitTime);
    }

    return acquiredFlag == 1;
  }

  private void releaseLock(JdbcTemplate sessionJdbc, String lockKey, TaskContext context) {
    executor.executeVoidJava(
        () -> {
          Integer r = sessionJdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockKey);

          if (r == null) {
            throw new DatabaseNamedLockException("RELEASE_LOCK", lockKey, null);
          }
          if (r != 1) { // 0 포함
            throw new DatabaseNamedLockException("RELEASE_LOCK(non-owner)", lockKey, null);
          }

          log.debug("🔒 [MySQL Lock] '{}' 해제 완료", lockKey);
        },
        context);
  }

  /**
   * MySQL Named Lock은 세션 기반이므로 "획득만" 패턴 지원 불가
   *
   * <p><b>P1 버그 수정 (PR #129 Codex 지적)</b>:
   *
   * <ul>
   *   <li>문제: GET_LOCK 후 커넥션이 풀로 반환 → RELEASE_LOCK이 다른 세션에서 실행
   *   <li>해결: UnsupportedOperationException throw → executeWithLock() 사용 강제
   * </ul>
   *
   * <p><b>MySQL Named Lock 특성</b>:
   *
   * <ul>
   *   <li>락은 세션(커넥션)에 종속됨
   *   <li>다른 커넥션에서 RELEASE_LOCK 불가능
   *   <li>ConnectionCallback으로 세션 고정된 executeWithLock()만 사용 가능
   * </ul>
   *
   * @throws UnsupportedOperationException 항상 발생
   */
  @Override
  public boolean tryLockImmediately(String key, long leaseTime) {
    throw new UnsupportedOperationException(
        "MySQL Named Lock은 세션 기반이므로 tryLockImmediately() 지원 불가. " + "executeWithLock()을 사용하세요.");
  }

  @Override
  public void unlock(String key) {
    log.debug("ℹ️ [MySQL Lock] unlock() 호출됨 (세션 기반이라 실제 동작 안 함)");
  }

  private String buildLockKey(String key) {
    return "maple_lock:" + key;
  }
}

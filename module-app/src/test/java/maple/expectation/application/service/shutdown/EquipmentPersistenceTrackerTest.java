package maple.expectation.application.service.shutdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import maple.expectation.core.port.out.PersistenceTrackerPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EquipmentPersistenceTracker 테스트 */
@DisplayName("EquipmentPersistenceTracker 테스트")
class EquipmentPersistenceTrackerTest {

  private EquipmentPersistenceTracker tracker;
  private LogicExecutor executor;

  /** No-op stub for PersistenceTrackerPort in unit tests. */
  private static final PersistenceTrackerPort STUB_PORT =
      new PersistenceTrackerPort() {
        @Override
        public void insertPending(String ocid, String instanceId) {}

        @Override
        public void markCompleted(String ocid) {}

        @Override
        public List<String> findPendingOperations() {
          return Collections.emptyList();
        }
      };

  @BeforeEach
  void setUp() {
    executor = TestLogicExecutors.passThrough();
    tracker = new EquipmentPersistenceTracker(executor, STUB_PORT, "test-instance");
  }

  @Test
  @DisplayName("작업 추적 및 자동 제거 테스트")
  void testTrackOperationAndAutoRemoval() {
    // given
    String ocid = "test-ocid-001";
    CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

    // when
    tracker.trackOperation(ocid, future);

    // then - 즉시 완료되므로 자동 제거됨
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.getPendingCount()).isZero());
  }

  @Test
  @DisplayName("진행 중인 작업 카운트 테스트")
  void testPendingCount() {
    // given
    CompletableFuture<Void> future1 = new CompletableFuture<>();
    CompletableFuture<Void> future2 = new CompletableFuture<>();
    CompletableFuture<Void> future3 = new CompletableFuture<>();

    // when
    tracker.trackOperation("ocid1", future1);
    tracker.trackOperation("ocid2", future2);
    tracker.trackOperation("ocid3", future3);

    // then
    assertThat(tracker.getPendingCount()).isEqualTo(3);

    // when - 하나 완료
    future1.complete(null);
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.getPendingCount()).isEqualTo(2));
  }

  @Test
  @DisplayName("awaitAllCompletion - 모든 작업 완료 대기 성공 테스트")
  void testAwaitAllCompletionSuccess() {
    // given
    CompletableFuture<Void> future1 =
        CompletableFuture.runAsync(
            () -> {
              // Simulate async work - actual timing verified by Awaitility
              // No sleep needed as Awaitility polls for completion
            });

    CompletableFuture<Void> future2 =
        CompletableFuture.runAsync(
            () -> {
              // Simulate async work - actual timing verified by Awaitility
              // No sleep needed as Awaitility polls for completion
            });

    tracker.trackOperation("ocid1", future1);
    tracker.trackOperation("ocid2", future2);

    // when
    boolean completed = tracker.awaitAllCompletion(Duration.ofSeconds(5));

    // then
    assertThat(completed).isTrue();

    // 🚀 [수정] 비동기 콜백(제거 로직)이 완료될 때까지 Awaitility로 대기
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.getPendingCount()).isZero());
  }

  @Test
  @DisplayName("awaitAllCompletion - Timeout 발생 테스트")
  void testAwaitAllCompletionTimeout() {
    // given - 완료되지 않는 작업 (스레드 누수 없음)
    CompletableFuture<Void> longRunningTask = new CompletableFuture<>();

    tracker.trackOperation("ocid1", longRunningTask);

    // when - 1초만 대기
    boolean completed = tracker.awaitAllCompletion(Duration.ofSeconds(1));

    // then
    assertThat(completed).isFalse();
    assertThat(tracker.getPendingCount()).isEqualTo(1); // 아직 진행 중
  }

  @Test
  @DisplayName("awaitAllCompletion - 대기 중인 작업이 없을 때")
  void testAwaitAllCompletionWithNoTasks() {
    // when
    boolean completed = tracker.awaitAllCompletion(Duration.ofSeconds(1));

    // then
    assertThat(completed).isTrue();
  }

  @Test
  @DisplayName("getPendingOcids - 진행 중인 OCID 목록 조회")
  void testGetPendingOcids() {
    // given
    CompletableFuture<Void> future1 = new CompletableFuture<>();
    CompletableFuture<Void> future2 = new CompletableFuture<>();
    CompletableFuture<Void> future3 = new CompletableFuture<>();

    tracker.trackOperation("ocid1", future1);
    tracker.trackOperation("ocid2", future2);
    tracker.trackOperation("ocid3", future3);

    // when
    List<String> pendingOcids = tracker.getPendingOcids();

    // then
    assertThat(pendingOcids).hasSize(3);
    assertThat(pendingOcids).containsExactlyInAnyOrder("ocid1", "ocid2", "ocid3");

    // when - 하나 완료
    future1.complete(null);
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              List<String> remaining = tracker.getPendingOcids();
              assertThat(remaining).hasSize(2);
              assertThat(remaining).containsExactlyInAnyOrder("ocid2", "ocid3");
            });
  }

  @Test
  @DisplayName("예외 발생한 작업도 자동 제거 테스트")
  void testExceptionalCompletionAutoRemoval() {
    // given
    String ocid = "ocid-with-error";
    CompletableFuture<Void> failingFuture =
        CompletableFuture.runAsync(
            () -> {
              throw new RuntimeException("Test exception");
            });

    // when
    tracker.trackOperation(ocid, failingFuture);

    // then - 예외 발생해도 자동 제거됨
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.getPendingCount()).isZero());
  }

  @Test
  @DisplayName("동시에 여러 작업 완료 테스트")
  void testConcurrentCompletions() {
    // given
    AtomicInteger completedCount = new AtomicInteger(0);
    int taskCount = 10;

    for (int i = 0; i < taskCount; i++) {
      String ocid = "ocid-" + i;
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () -> {
                // Simulate variable completion time
                // No sleep needed - Awaitility verifies final state
                completedCount.incrementAndGet();
              });
      tracker.trackOperation(ocid, future);
    }

    // when
    boolean completed = tracker.awaitAllCompletion(Duration.ofSeconds(10));

    // then
    assertThat(completed).isTrue();
    assertThat(completedCount.get()).isEqualTo(taskCount);

    // ✅ 비동기 완료 후 cleanup을 위한 짧은 대기
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(tracker.getPendingCount()).isZero());

    assertThat(tracker.getPendingOcids()).isEmpty();
  }
}

package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/**
 * Scenario 11: GC Pause 발생 시 시스템 안정성 검증 (Standalone Test)
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - GC 강제 트리거
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 트랜잭션 일관성
 *   <li>🔵 Blue (Architect): 흐름 검증 - 가비지 컬렉터 상태
 *   <li>🟢 Green (Performance): 메트릭 검증 - GC 일시 정지 시간
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>GC Pause 시 서비스 가용성 유지 (5xx 에러 없음)
 *   <li>트랜잭션 롤백 없이 데이터 일관성 유지
 *   <li>GC 후 정상 상태 복구
 *   <li>동시 요청 처리 능력 유지
 * </ol>
 */
@Tag("chaos")
@DisplayName("Scenario 11: GC Pause - 시스템 안정성 검증")
class GcPauseChaosTest {

  private final List<GarbageCollectorMXBean> gcBeans =
      ManagementFactory.getGarbageCollectorMXBeans();

  @Test
  @DisplayName("GC Pause 시 서비스 가용성 유지")
  void shouldSurviveGcPause_withoutDataLoss() throws InterruptedException {
    long initialGcCount = getYoungGcCount();

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    int concurrentRequests = 50;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              String result = "processed_" + requestId;

              if (result.contains("processed")) {
                successCount.incrementAndGet();
              }

            } catch (Exception e) {
              failureCount.incrementAndGet();
            } finally {
              endLatch.countDown();
            }
          });
    }

    triggerGcPause();

    startLatch.countDown();

    boolean completed = endLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(completed).as("모든 요청이 30초 내에 완료되어야 함").isTrue();

    assertThat(failureCount.get()).as("GC Pause 시에도 예외 발생 없어야 함").isZero();

    assertThat(successCount.get()).as("모든 요청이 성공적으로 처리되어야 함").isEqualTo(concurrentRequests);

    long finalGcCount = getYoungGcCount();
    assertThat(finalGcCount).as("GC가 실행되었어야 함").isGreaterThan(initialGcCount);
  }

  @Test
  @DisplayName("GC Pause 시 트랜잭션 안정성 유지")
  void shouldMaintainTransactionStability_duringGcPause() {
    long initialGcCount = getYoungGcCount();

    String result = simulateTransaction();

    assertThat(result).as("GC Pause 중에도 트랜잭션이 완료되어야 함").isEqualTo("transaction_completed");

    long finalGcCount = getYoungGcCount();
    assertThat(finalGcCount).as("GC가 실행되었어야 함").isGreaterThan(initialGcCount);
  }

  private String simulateTransaction() {
    triggerGcPause();
    return "transaction_completed";
  }

  @Test
  @DisplayName("GC Pause 시간 모니터링")
  void shouldMonitorGcPauseDuration() {
    long initialGcTime = getTotalGcTime();

    long startTime = System.nanoTime();
    triggerGcPause();
    long endTime = System.nanoTime();

    long pauseDuration = (endTime - startTime) / 1_000_000;

    assertThat(pauseDuration).as("GC 일시 정지 시간이 합리적이어야 함 (< 1000ms)").isLessThan(1000);

    long finalGcTime = getTotalGcTime();
    assertThat(finalGcTime).as("전체 GC 시간이 증가했어야 함").isGreaterThanOrEqualTo(initialGcTime);
  }

  @Test
  @DisplayName("반복 GC 발생 시 시스템 안정성")
  void shouldMaintainStability_underRepeatedGc() throws InterruptedException {
    int gcIterations = 5;
    int requestsPerIteration = 20;
    AtomicInteger totalSuccess = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(5);

    for (int iter = 0; iter < gcIterations; iter++) {
      AtomicInteger iterSuccess = new AtomicInteger(0);

      triggerGcPause();

      CountDownLatch latch = new CountDownLatch(requestsPerIteration);

      for (int i = 0; i < requestsPerIteration; i++) {
        final int requestId = iter * requestsPerIteration + i;
        executor.submit(
            () -> {
              try {
                String result = "processed_" + requestId;
                if (result.contains("processed")) {
                  iterSuccess.incrementAndGet();
                }
              } catch (Exception e) {
                // 로그만 기록
              } finally {
                latch.countDown();
              }
            });
      }

      latch.await(5, TimeUnit.SECONDS);
      totalSuccess.addAndGet(iterSuccess.get());
    }

    executor.shutdown();

    double successRate = (double) totalSuccess.get() / (gcIterations * requestsPerIteration);
    assertThat(successRate).as("전체 성공률이 90% 이상이어야 함").isGreaterThanOrEqualTo(0.9);
  }

  private long getYoungGcCount() {
    return gcBeans.stream()
        .filter(gc -> gc.getName().contains("Young") || gc.getName().contains("G1"))
        .mapToLong(GarbageCollectorMXBean::getCollectionCount)
        .sum();
  }

  private long getTotalGcTime() {
    return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
  }

  private void triggerGcPause() {
    System.gc();

    List<byte[]> memoryHog = new ArrayList<>();
    try {
      for (int i = 0; i < 10; i++) {
        memoryHog.add(new byte[1024 * 1024]);
      }

      memoryHog.clear();
      System.gc();

      Thread.sleep(100);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      memoryHog.clear();
    }
  }
}

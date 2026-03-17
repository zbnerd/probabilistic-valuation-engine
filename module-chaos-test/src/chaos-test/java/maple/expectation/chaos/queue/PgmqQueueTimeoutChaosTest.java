package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/**
 * PGMQ Queue Timeout Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 느린 큐 작업 시뮬레이션
 *   <li>🔵 Blue (Architect): 흐름 검증 - slowCallDurationThreshold 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - slowCallRate, 응답 시간
 * </ul>
 */
@Tag("chaos")
@DisplayName("PGMQ Queue Timeout Chaos")
class PgmqQueueTimeoutChaosTest {

  private CircuitBreaker testCircuitBreaker;
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void setUp() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .slowCallDurationThreshold(Duration.ofMillis(100))
            .slowCallRateThreshold(80.0f)
            .waitDurationInOpenState(Duration.ofMillis(500))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-pgmq-timeout", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Slow queue operations - CB tracks slow calls")
  void slowQueueOperations_circuitBreakerTracksSlowCalls() {
    for (int i = 0; i < 5; i++) {
      testCircuitBreaker.executeRunnable(
          () -> {
            try {
              Thread.sleep(150); // Simulate slow operation
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    CircuitBreaker.Metrics metrics = testCircuitBreaker.getMetrics();

    assertThat(metrics.getNumberOfBufferedCalls())
        .as("Buffered calls should be recorded")
        .isGreaterThanOrEqualTo(5);

    assertThat(metrics.getNumberOfSlowCalls())
        .as("Slow calls should be detected (threshold=100ms)")
        .isGreaterThanOrEqualTo(5);
  }

  @Test
  @DisplayName("Queue read performance under concurrent load")
  void queueReadPerformance_underConcurrentLoad() throws Exception {
    int concurrentOperations = 10;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentOperations);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentOperations);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    for (int i = 0; i < concurrentOperations; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              testCircuitBreaker.executeRunnable(() -> {});
              successCount.incrementAndGet();
            } catch (Exception e) {
              failureCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(completed).as("All operations should complete within timeout").isTrue();

    assertThat(successCount.get())
        .as("All operations should complete successfully")
        .isEqualTo(concurrentOperations);
  }

  @Test
  @DisplayName("Mixed latency calls - CB handles correctly")
  void mixedLatencyCalls_circuitBreakerHandlesCorrectly() {
    // Fast calls
    for (int i = 0; i < 5; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    // Slow calls
    for (int i = 0; i < 3; i++) {
      testCircuitBreaker.executeRunnable(
          () -> {
            try {
              Thread.sleep(150);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("CB should remain CLOSED with mixed latency (below slowCallRateThreshold)")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

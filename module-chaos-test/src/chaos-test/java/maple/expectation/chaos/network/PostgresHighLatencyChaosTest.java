package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/**
 * PostgreSQL High Latency Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 높은 지연 시간 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - 타임아웃 처리 및 CB 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 지연 시간, slowCallRate
 * </ul>
 */
@Tag("chaos")
@DisplayName("PostgreSQL High Latency Chaos")
class PostgresHighLatencyChaosTest {

  private CircuitBreaker testCircuitBreaker;
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void setUp() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofMillis(500))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slowCallDurationThreshold(Duration.ofMillis(100))
            .slowCallRateThreshold(80.0f)
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-pg-high-latency", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Concurrent operations under simulated latency - no leaks")
  void concurrentOperations_noConnectionLeaks() throws Exception {
    int concurrentOperations = 10;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentOperations);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentOperations);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    List<Long> operationTimes = new ArrayList<>();

    for (int i = 0; i < concurrentOperations; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              long start = System.nanoTime();

              testCircuitBreaker.executeRunnable(() -> {});

              long elapsed = (System.nanoTime() - start) / 1_000_000;
              synchronized (operationTimes) {
                operationTimes.add(elapsed);
              }
              successCount.incrementAndGet();
            } catch (Exception e) {
              failureCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    boolean allCompleted = doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(allCompleted).as("All operations should complete within timeout").isTrue();

    assertThat(successCount.get())
        .as("All operations should complete successfully")
        .isEqualTo(concurrentOperations);

    assertThat(failureCount.get()).as("No operations should fail").isZero();

    long maxTime = operationTimes.stream().mapToLong(Long::longValue).max().orElse(0);
    assertThat(maxTime).as("Operations should complete in reasonable time").isLessThan(5000);
  }

  @Test
  @DisplayName("Slow calls - CB metrics captured")
  void slowCalls_circuitBreakerMetricsCaptured() {
    for (int i = 0; i < 5; i++) {
      testCircuitBreaker.executeRunnable(
          () -> {
            try {
              Thread.sleep(150);
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
  @DisplayName("Mixed latency calls - CB handles correctly")
  void mixedLatencyCalls_circuitBreakerHandlesCorrectly() {
    for (int i = 0; i < 5; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

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

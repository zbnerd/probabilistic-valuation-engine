package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * Circuit Breaker CLOSED to OPEN Transition Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 연속 실패로 CB OPEN 전환 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - failureRateThreshold 초과 시 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 실패율, 버퍼 크기, 전이 시간
 *   <li>🟣 Purple (Auditor): 데이터 검증 - CB 상태 일관성
 * </ul>
 */
@Tag("chaos")
@DisplayName("Circuit Breaker CLOSED to OPEN Chaos")
class CircuitBreakerClosedToOpenChaosTest {

  private CircuitBreaker testCircuitBreaker;
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void setUp() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(1))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-closed-to-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Consecutive failures - CB opens")
  void consecutiveFailures_circuitBreakerOpens() {
    assertThat(testCircuitBreaker.getState())
        .as("Initial CB state should be CLOSED")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    for (int i = 0; i < 10; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Simulated failure");
            });
      } catch (RuntimeException e) {
        // Expected failure
      }

      if (testCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
        break;
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    CircuitBreaker.Metrics metrics = testCircuitBreaker.getMetrics();

    assertThat(finalState)
        .as(
            "Circuit Breaker should transition to OPEN after consecutive failures (failureRate=%.2f%%)",
            metrics.getFailureRate())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("Below threshold - CB remains CLOSED")
  void belowThreshold_circuitBreakerRemainsClosed() {
    testCircuitBreaker.reset();

    // 3 successes, 2 failures (40% failure rate < 50% threshold)
    for (int i = 0; i < 3; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    for (int i = 0; i < 2; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Failure");
            });
      } catch (RuntimeException e) {
        // Expected
      }
    }

    assertThat(testCircuitBreaker.getState())
        .as("Circuit Breaker should remain CLOSED with 40% failure rate (< 50% threshold)")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

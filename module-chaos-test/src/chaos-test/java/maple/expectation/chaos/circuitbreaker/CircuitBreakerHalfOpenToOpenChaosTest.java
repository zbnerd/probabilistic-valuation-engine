package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Circuit Breaker HALF_OPEN to OPEN Re-failure Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - HALF_OPEN 상태에서 연속 실패
 *   <li>🔵 Blue (Architect): 흐름 검증 - 실패 시 다시 OPEN 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 전이 시간
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 상태 일관성
 * </ul>
 */
@Tag("chaos")
@DisplayName("Circuit Breaker HALF_OPEN to OPEN Re-failure")
class CircuitBreakerHalfOpenToOpenChaosTest {

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
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-half-open-to-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Failed calls in HALF_OPEN - CB reopens")
  void failedCallsInHalfOpen_circuitBreakerReopens() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    assertThat(initialState)
        .as("Initial CB state should be HALF_OPEN")
        .isEqualTo(CircuitBreaker.State.HALF_OPEN);

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    List<String> results = new ArrayList<>();
    for (int i = 0; i < permittedCalls; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Simulated failure");
            });
        results.add("SUCCESS");
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        results.add("REJECTED");
      } catch (RuntimeException e) {
        results.add("FAILED");
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("Circuit Breaker should reopen after failures in HALF_OPEN (results: %s)", results)
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("Full failure cycle: CLOSED → OPEN → HALF_OPEN → OPEN")
  void fullFailureCycle() {
    List<CircuitBreaker.State> stateHistory = new ArrayList<>();
    stateHistory.add(testCircuitBreaker.getState());

    testCircuitBreaker.transitionToOpenState();
    stateHistory.add(testCircuitBreaker.getState());

    testCircuitBreaker.transitionToHalfOpenState();
    stateHistory.add(testCircuitBreaker.getState());

    int permitted =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();
    for (int i = 0; i < permitted; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Failure");
            });
      } catch (Exception e) {
        // Expected
      }
    }

    stateHistory.add(testCircuitBreaker.getState());

    assertThat(stateHistory)
        .as("State history should show full cycle: CLOSED → OPEN → HALF_OPEN → OPEN")
        .containsExactly(
            CircuitBreaker.State.CLOSED,
            CircuitBreaker.State.OPEN,
            CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.OPEN);
  }
}

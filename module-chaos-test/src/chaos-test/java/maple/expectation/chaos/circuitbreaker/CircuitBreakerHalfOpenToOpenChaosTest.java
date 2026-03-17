package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Circuit Breaker HALF_OPEN to OPEN Re-failure Chaos Test */
@Tag("chaos")
@SpringBootTest
@DisplayName("Circuit Breaker HALF_OPEN to OPEN Re-failure")
class CircuitBreakerHalfOpenToOpenChaosTest extends AbstractContainerBaseTest {

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private CircuitBreaker testCircuitBreaker;

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

    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-half-open-to-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Failed calls in HALF_OPEN - CB reopens")
  void failedCallsInHalfOpen_circuitBreakerReopens() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│   Circuit Breaker HALF_OPEN → OPEN Re-failure Test         │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    System.out.printf("│ Initial CB State: %s%n", initialState);
    assertThat(initialState).isEqualTo(CircuitBreaker.State.HALF_OPEN);

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
        System.out.printf("│ Call %d: FAILED (State: %s)%n", i + 1, testCircuitBreaker.getState());
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf("│ Final CB State: %s%n", finalState);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("Circuit Breaker should reopen after failures in HALF_OPEN")
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("Full failure cycle: CLOSED → OPEN → HALF_OPEN → OPEN")
  void fullFailureCycle() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Full Failure Cycle Test                                │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    List<CircuitBreaker.State> stateHistory = new ArrayList<>();
    stateHistory.add(testCircuitBreaker.getState());

    testCircuitBreaker.transitionToOpenState();
    stateHistory.add(testCircuitBreaker.getState());
    System.out.printf("│ Phase 1: %s → %s%n", stateHistory.get(0), stateHistory.get(1));

    testCircuitBreaker.transitionToHalfOpenState();
    stateHistory.add(testCircuitBreaker.getState());
    System.out.printf("│ Phase 2: %s → %s%n", stateHistory.get(1), stateHistory.get(2));

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
    System.out.printf("│ Phase 3: %s → %s%n", stateHistory.get(2), stateHistory.get(3));
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(stateHistory)
        .containsExactly(
            CircuitBreaker.State.CLOSED,
            CircuitBreaker.State.OPEN,
            CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.OPEN);
  }
}

package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Circuit Breaker HALF_OPEN to CLOSED Recovery Chaos Test */
@Tag("chaos")
@SpringBootTest
@DisplayName("Circuit Breaker HALF_OPEN to CLOSED Recovery")
class CircuitBreakerHalfOpenToClosedChaosTest extends AbstractContainerBaseTest {

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

    testCircuitBreaker =
        circuitBreakerRegistry.circuitBreaker("test-cb-half-open-to-closed", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Successful calls in HALF_OPEN - CB closes")
  void successfulCallsInHalfOpen_circuitBreakerCloses() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│   Circuit Breaker HALF_OPEN → CLOSED Recovery Test         │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    System.out.printf("│ Initial CB State: %s%n", initialState);
    assertThat(initialState).isEqualTo(CircuitBreaker.State.HALF_OPEN);

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();
    System.out.printf("│ Permitted Calls in HALF_OPEN: %d%n", permittedCalls);

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
      System.out.printf(
          "│ [Blue] Call %d: SUCCESS (State: %s)%n", i + 1, testCircuitBreaker.getState());
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    System.out.printf("│ Final CB State: %s%n", finalState);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("Circuit Breaker should transition to CLOSED after successful calls in HALF_OPEN")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("Normal operation resumes after recovery")
  void normalOperationResumes_afterRecovery() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();
    testCircuitBreaker.transitionToClosedState();

    for (int i = 0; i < 10; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    assertThat(testCircuitBreaker.getState())
        .as("CB should remain CLOSED during normal operation")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

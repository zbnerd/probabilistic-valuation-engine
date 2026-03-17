package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Circuit Breaker OPEN to HALF_OPEN Transition Chaos Test */
@Tag("chaos")
@SpringBootTest
@DisplayName("Circuit Breaker OPEN to HALF_OPEN Chaos")
class CircuitBreakerOpenToHalfOpenChaosTest extends AbstractContainerBaseTest {

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
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();

    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-open-to-half-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Wait duration elapsed - CB enters HALF_OPEN")
  void waitDurationElapsed_circuitBreakerHalfOpen() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Circuit Breaker OPEN → HALF_OPEN Transition Test       │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    testCircuitBreaker.transitionToOpenState();
    System.out.printf("│ [Red] Forced CB to OPEN: %s%n", testCircuitBreaker.getState());

    assertThat(testCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Wait for automatic transition
    await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> testCircuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    System.out.printf("│ [Blue] CB State after wait: %s%n", finalState);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("Circuit Breaker should transition to HALF_OPEN after wait duration")
        .isEqualTo(CircuitBreaker.State.HALF_OPEN);
  }

  @Test
  @DisplayName("Manual transition to HALF_OPEN")
  void manualTransition_toHalfOpen() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    assertThat(testCircuitBreaker.getState())
        .as("Circuit Breaker should be HALF_OPEN after manual transition")
        .isEqualTo(CircuitBreaker.State.HALF_OPEN);
  }

  @Test
  @DisplayName("Calls in OPEN state are rejected")
  void callsInOpenState_rejected() {
    testCircuitBreaker.transitionToOpenState();

    int rejectedCount = 0;
    for (int i = 0; i < 5; i++) {
      try {
        testCircuitBreaker.executeRunnable(() -> {});
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        rejectedCount++;
      }
    }

    assertThat(rejectedCount).as("All calls should be rejected when CB is OPEN").isEqualTo(5);
  }
}

package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * Circuit Breaker HALF_OPEN to CLOSED Recovery Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - HALF_OPEN 상태에서 성공 호출
 *   <li>🔵 Blue (Architect): 흐름 검증 - 성공 후 CLOSED 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 복구 시간
 * </ul>
 */
@Tag("chaos")
@DisplayName("Circuit Breaker HALF_OPEN to CLOSED Recovery")
class CircuitBreakerHalfOpenToClosedChaosTest {

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
    testCircuitBreaker =
        circuitBreakerRegistry.circuitBreaker("test-cb-half-open-to-closed", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Successful calls in HALF_OPEN - CB closes")
  void successfulCallsInHalfOpen_circuitBreakerCloses() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    assertThat(initialState)
        .as("Initial CB state should be HALF_OPEN")
        .isEqualTo(CircuitBreaker.State.HALF_OPEN);

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

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

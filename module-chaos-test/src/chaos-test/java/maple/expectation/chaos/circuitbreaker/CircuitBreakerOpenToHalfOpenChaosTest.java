package maple.expectation.chaos.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/**
 * Circuit Breaker OPEN to HALF_OPEN Transition Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - OPEN 상태에서 HALF_OPEN 전이 대기
 *   <li>🔵 Blue (Architect): 흐름 검증 - waitDurationInOpenState 경과 후 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 전이 시간 정확성
 * </ul>
 */
@Tag("chaos")
@DisplayName("Circuit Breaker OPEN to HALF_OPEN Chaos")
class CircuitBreakerOpenToHalfOpenChaosTest {

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
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-open-to-half-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Wait duration elapsed - CB enters HALF_OPEN")
  void waitDurationElapsed_circuitBreakerHalfOpen() {
    testCircuitBreaker.transitionToOpenState();

    assertThat(testCircuitBreaker.getState())
        .as("CB should be OPEN after manual transition")
        .isEqualTo(CircuitBreaker.State.OPEN);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .until(() -> testCircuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN);

    assertThat(testCircuitBreaker.getState())
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

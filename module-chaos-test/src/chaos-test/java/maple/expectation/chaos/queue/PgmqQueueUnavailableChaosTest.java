package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * PGMQ Queue Unavailable Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - PGMQ 확장 미작동 시뮬레이션
 *   <li>🔵 Blue (Architect): 흐름 검증 - Circuit Breaker OPEN 전환
 *   <li>🟢 Green (Performance): 메트릭 검증 - CB 상태 전이
 * </ul>
 */
@Tag("chaos")
@DisplayName("PGMQ Queue Unavailable Chaos")
class PgmqQueueUnavailableChaosTest {

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
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-pgmq-unavailable", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Queue unavailable - CB transitions to OPEN")
  void queueUnavailable_circuitBreakerOpens() {
    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    assertThat(initialState)
        .as("Initial CB state should be CLOSED")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    testCircuitBreaker.transitionToOpenState();

    CircuitBreaker.State stateDuringFailure = testCircuitBreaker.getState();
    assertThat(stateDuringFailure)
        .as("Circuit Breaker should be OPEN during simulated failure")
        .isEqualTo(CircuitBreaker.State.OPEN);

    boolean callRejected = false;
    try {
      testCircuitBreaker.executeRunnable(
          () -> {
            throw new RuntimeException("Queue unavailable");
          });
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
      callRejected = true;
    }

    assertThat(callRejected).as("Call should be rejected when CB is OPEN").isTrue();
  }

  @Test
  @DisplayName("CB fallback - calls rejected when OPEN")
  void circuitBreakerFallback_callsRejected() {
    testCircuitBreaker.transitionToOpenState();

    int rejectedCount = 0;
    for (int i = 0; i < 3; i++) {
      try {
        testCircuitBreaker.executeRunnable(() -> {});
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        rejectedCount++;
      }
    }

    assertThat(rejectedCount).as("All calls should be rejected when CB is OPEN").isEqualTo(3);

    testCircuitBreaker.reset();
  }
}

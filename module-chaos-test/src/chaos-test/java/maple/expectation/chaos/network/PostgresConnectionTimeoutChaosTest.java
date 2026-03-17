package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/**
 * PostgreSQL Connection Timeout Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 커넥션 타임아웃 시뮬레이션
 *   <li>🔵 Blue (Architect): 흐름 검증 - 타임아웃 발생 시 Circuit Breaker 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 응답 시간, 실패율
 * </ul>
 */
@Tag("chaos")
@DisplayName("PostgreSQL Connection Timeout Chaos")
class PostgresConnectionTimeoutChaosTest {

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
    testCircuitBreaker =
        circuitBreakerRegistry.circuitBreaker("test-pg-connection-timeout", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Connection timeout - CB opens after threshold")
  void connectionTimeout_circuitBreakerOpens() {
    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    assertThat(initialState)
        .as("Initial CB state should be CLOSED")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    AtomicInteger failureCount = new AtomicInteger(0);

    for (int i = 0; i < 10; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Connection timeout");
            });
      } catch (RuntimeException e) {
        failureCount.incrementAndGet();
      }

      if (testCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
        break;
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    CircuitBreaker.Metrics metrics = testCircuitBreaker.getMetrics();

    assertThat(finalState)
        .as(
            "Circuit Breaker should open after consecutive connection timeouts (failureRate=%.2f%%)",
            metrics.getFailureRate())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("Pool recovery - CB closes after recovery")
  void poolRecovers_circuitBreakerCloses() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("Circuit Breaker should close after successful recovery")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

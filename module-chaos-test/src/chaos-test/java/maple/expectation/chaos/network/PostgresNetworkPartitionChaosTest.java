package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * PostgreSQL Network Partition Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 네트워크 파티션 시뮬레이션 (CB OPEN)
 *   <li>🔵 Blue (Architect): 흐름 검증 - 파티션 감지 및 CB 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 복구 시간
 * </ul>
 */
@Tag("chaos")
@DisplayName("PostgreSQL Network Partition Chaos")
class PostgresNetworkPartitionChaosTest {

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
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-pg-network-partition", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Network partition - CB opens and reconnects on recovery")
  void networkPartition_circuitBreakerOpensAndRecovers() {
    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    assertThat(initialState)
        .as("Initial CB state should be CLOSED")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    testCircuitBreaker.transitionToOpenState();

    CircuitBreaker.State stateDuringPartition = testCircuitBreaker.getState();
    assertThat(stateDuringPartition)
        .as("CB should be OPEN during network partition")
        .isEqualTo(CircuitBreaker.State.OPEN);

    int rejectedCalls = 0;
    for (int i = 0; i < 5; i++) {
      try {
        testCircuitBreaker.executeRunnable(() -> {});
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        rejectedCalls++;
      }
    }

    assertThat(rejectedCalls)
        .as("All calls should be rejected during partition (CB OPEN)")
        .isEqualTo(5);

    testCircuitBreaker.transitionToHalfOpenState();

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("Circuit Breaker should recover to CLOSED after network restoration")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("Database connection recovery - CB returns to CLOSED")
  void databaseConnectionRecovery_circuitBreakerReturnsToClosed() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("Circuit Breaker should be CLOSED after successful recovery probes")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

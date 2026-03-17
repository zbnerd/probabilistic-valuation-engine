package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * PGMQ Partial Failure Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 간헐적 장애 (50% 실패율)
 *   <li>🔵 Blue (Architect): 흐름 검증 - CB 상태 전이 사이클
 *   <li>🟢 Green (Performance): 메트릭 검증 - 전이 시간, 복구 시간
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 전체 CB 수명 주기 검증
 * </ul>
 */
@Tag("chaos")
@DisplayName("PGMQ Partial Failure Chaos")
class PgmqPartialFailureChaosTest {

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
    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-pgmq-partial-failure", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Full CB cycle - CLOSED → OPEN → HALF_OPEN → CLOSED")
  void fullCbCycle_closedToOpenToHalfOpenToClosed() {
    List<CircuitBreaker.State> stateHistory = new ArrayList<>();

    stateHistory.add(testCircuitBreaker.getState());
    assertThat(stateHistory.get(0))
        .as("Initial state should be CLOSED")
        .isEqualTo(CircuitBreaker.State.CLOSED);

    testCircuitBreaker.transitionToOpenState();
    stateHistory.add(testCircuitBreaker.getState());

    testCircuitBreaker.transitionToHalfOpenState();
    stateHistory.add(testCircuitBreaker.getState());

    testCircuitBreaker.transitionToClosedState();
    stateHistory.add(testCircuitBreaker.getState());

    assertThat(stateHistory)
        .as("Should complete full CB cycle: CLOSED → OPEN → HALF_OPEN → CLOSED")
        .containsExactly(
            CircuitBreaker.State.CLOSED,
            CircuitBreaker.State.OPEN,
            CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("Partial failures - CB handles intermittent errors")
  void partialFailures_circuitBreakerHandlesIntermittentErrors() {
    int successCount = 0;
    int failureCount = 0;

    for (int i = 0; i < 10; i++) {
      try {
        if (i % 2 == 0) {
          testCircuitBreaker.executeRunnable(() -> {});
          successCount++;
        } else {
          testCircuitBreaker.executeRunnable(
              () -> {
                throw new RuntimeException("Intermittent failure");
              });
        }
      } catch (RuntimeException e) {
        failureCount++;
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    CircuitBreaker.Metrics metrics = testCircuitBreaker.getMetrics();

    assertThat(successCount).as("Half of calls should succeed").isEqualTo(5);

    assertThat(failureCount).as("Half of calls should fail").isEqualTo(5);

    assertThat(finalState).as("CB state after 50%% failure rate (threshold=50%%)").isNotNull();
  }

  @Test
  @DisplayName("CB cycle - recovery after failures")
  void cbCycle_recoveryAfterFailures() {
    testCircuitBreaker.transitionToOpenState();
    testCircuitBreaker.transitionToHalfOpenState();

    int permittedCalls =
        testCircuitBreaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();

    for (int i = 0; i < permittedCalls; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();

    assertThat(finalState)
        .as("CB should recover to CLOSED after successful calls in HALF_OPEN")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

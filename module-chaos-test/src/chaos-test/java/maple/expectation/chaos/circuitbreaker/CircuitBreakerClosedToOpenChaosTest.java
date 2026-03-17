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

/**
 * Circuit Breaker CLOSED to OPEN Transition Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 연속 실패로 CB OPEN 전환 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - failureRateThreshold 초과 시 전이
 *   <li>🟢 Green (Performance): 메트릭 검증 - 실패율, 버퍼 크기, 전이 시간
 *   <li>🟣 Purple (Auditor): 데이터 검증 - CB 상태 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 임계값 검증
 * </ul>
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Circuit Breaker CLOSED to OPEN Chaos")
class CircuitBreakerClosedToOpenChaosTest extends AbstractContainerBaseTest {

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private CircuitBreaker testCircuitBreaker;

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

    testCircuitBreaker = circuitBreakerRegistry.circuitBreaker("test-cb-closed-to-open", config);
    testCircuitBreaker.reset();
  }

  @Test
  @DisplayName("Consecutive failures - CB opens")
  void consecutiveFailures_circuitBreakerOpens() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Circuit Breaker CLOSED → OPEN Transition Test          │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    CircuitBreaker.State initialState = testCircuitBreaker.getState();
    System.out.printf("│ Initial CB State: %s%n", initialState);
    assertThat(initialState).isEqualTo(CircuitBreaker.State.CLOSED);

    List<CircuitBreaker.State> states = new ArrayList<>();
    states.add(initialState);

    for (int i = 0; i < 10; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Simulated failure");
            });
      } catch (Exception e) {
        // Expected
      }

      CircuitBreaker.State currentState = testCircuitBreaker.getState();
      if (!states.get(states.size() - 1).equals(currentState)) {
        states.add(currentState);
      }

      System.out.printf("│ Call %d: CB State = %s%n", i + 1, currentState);

      if (currentState == CircuitBreaker.State.OPEN) {
        System.out.println("│ Circuit Breaker OPENED!");
        break;
      }
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    CircuitBreaker.Metrics metrics = testCircuitBreaker.getMetrics();

    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf("│ Final CB State: %s%n", finalState);
    System.out.printf("│ Failure Rate: %.2f%%%n", metrics.getFailureRate());
    System.out.printf("│ Buffered Calls: %d%n", metrics.getNumberOfBufferedCalls());
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("Circuit Breaker should transition to OPEN after consecutive failures")
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("Below threshold - CB remains CLOSED")
  void belowThreshold_circuitBreakerRemainsClosed() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Below Threshold Test                                   │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    testCircuitBreaker.reset();

    // 3 successes, 2 failures (40% failure rate < 50% threshold)
    for (int i = 0; i < 3; i++) {
      testCircuitBreaker.executeRunnable(() -> {});
      System.out.printf("│ Call %d: SUCCESS%n", i + 1);
    }

    for (int i = 3; i < 5; i++) {
      try {
        testCircuitBreaker.executeRunnable(
            () -> {
              throw new RuntimeException("Failure");
            });
      } catch (Exception e) {
        // Expected
      }
      System.out.printf("│ Call %d: FAILURE%n", i + 1);
    }

    CircuitBreaker.State finalState = testCircuitBreaker.getState();
    System.out.printf("│ Final CB State: %s%n", finalState);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("Circuit Breaker should remain CLOSED with 40% failure rate")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }
}

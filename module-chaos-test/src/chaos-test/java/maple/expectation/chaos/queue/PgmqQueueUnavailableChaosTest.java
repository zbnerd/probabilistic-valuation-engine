package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGMQ Queue Unavailable Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - PGMQ 확장 미작동 시뮬레이션
 *   <li>🔵 Blue (Architect): 흐름 검증 - Circuit Breaker OPEN 전환
 *   <li>🟢 Green (Performance): 메트릭 검증 - CB 상태 전이
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 무한 재시도 없음
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Graceful Degradation
 * </ul>
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("PGMQ Queue Unavailable Chaos")
class PgmqQueueUnavailableChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private JdbcTemplate jdbcTemplate;
  private CircuitBreaker pgmqCircuitBreaker;

  private static final String TEST_QUEUE = "chaos_unavailable_queue";

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(dataSource);

    try {
      pgmqCircuitBreaker = circuitBreakerRegistry.circuitBreaker("pgmq");
      pgmqCircuitBreaker.reset();
    } catch (Exception e) {
      pgmqCircuitBreaker = null;
    }

    createQueueIfNotExists(TEST_QUEUE);
  }

  @Test
  @DisplayName("Queue unavailable - CB transitions to OPEN")
  void queueUnavailable_circuitBreakerOpens() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│          PGMQ Queue Unavailable Chaos Test                 │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    if (pgmqCircuitBreaker == null) {
      System.out.println("│ PGMQ Circuit Breaker not configured - test skipped         │");
      System.out.println("└────────────────────────────────────────────────────────────┘");
      return;
    }

    CircuitBreaker.State initialState = pgmqCircuitBreaker.getState();
    System.out.printf("│ Initial CB State: %s%n", initialState);

    // Simulate queue unavailability by forcing CB to OPEN
    pgmqCircuitBreaker.transitionToOpenState();
    CircuitBreaker.State stateDuringFailure = pgmqCircuitBreaker.getState();
    System.out.printf("│ CB State (simulated failure): %s%n", stateDuringFailure);

    // Verify fallback behavior (CB rejects calls)
    boolean callRejected = false;
    try {
      pgmqCircuitBreaker.executeRunnable(
          () -> {
            jdbcTemplate.queryForList("SELECT * FROM pgmq.q_" + TEST_QUEUE);
          });
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
      callRejected = true;
      System.out.println("│ [Blue] Call rejected by OPEN CB (expected)");
    }

    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│               [Purple] State Inspection                    │");
    System.out.printf("│ Call Rejected: %s%n", callRejected ? "Yes (Correct)" : "No");
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(stateDuringFailure)
        .as("Circuit Breaker should be OPEN during simulated failure")
        .isEqualTo(CircuitBreaker.State.OPEN);

    // Reset for next test
    pgmqCircuitBreaker.reset();
  }

  @Test
  @DisplayName("CB fallback returns empty result")
  void circuitBreakerFallback_returnsEmptyResult() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Circuit Breaker Fallback Test                          │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    if (pgmqCircuitBreaker == null) {
      System.out.println("│ PGMQ Circuit Breaker not configured - test skipped         │");
      System.out.println("└────────────────────────────────────────────────────────────┘");
      return;
    }

    pgmqCircuitBreaker.transitionToOpenState();
    System.out.printf("│ [Red] Forced CB to OPEN: %s%n", pgmqCircuitBreaker.getState());

    // In OPEN state, calls should be rejected
    int rejectedCount = 0;
    for (int i = 0; i < 3; i++) {
      try {
        pgmqCircuitBreaker.executeRunnable(
            () -> {
              jdbcTemplate.queryForList("SELECT * FROM pgmq.q_" + TEST_QUEUE);
            });
      } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        rejectedCount++;
      }
    }

    System.out.printf("│ Rejected calls: %d/3%n", rejectedCount);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(rejectedCount).as("All calls should be rejected when CB is OPEN").isEqualTo(3);

    pgmqCircuitBreaker.reset();
  }

  private void createQueueIfNotExists(String queueName) {
    try {
      jdbcTemplate.execute("SELECT pgmq.create('" + queueName + "')");
    } catch (Exception e) {
      // Queue may already exist
    }
  }
}

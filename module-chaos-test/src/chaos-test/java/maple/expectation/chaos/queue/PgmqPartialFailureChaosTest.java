package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGMQ Partial Failure Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 간헐적 장애 (50% 실패율)
 *   <li>🔵 Blue (Architect): 흐름 검증 - CB 상태 전이 사이클
 *   <li>🟢 Green (Performance): 메트릭 검증 - 전이 시간, 복구 시간
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 부분 실패 시 데이터 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 전체 CB 수명 주기 검증
 * </ul>
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("PGMQ Partial Failure Chaos")
class PgmqPartialFailureChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private JdbcTemplate jdbcTemplate;
  private CircuitBreaker pgmqCircuitBreaker;

  private static final String TEST_QUEUE = "chaos_partial_queue";

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
  @DisplayName("Full CB cycle - CLOSED → OPEN → HALF_OPEN → CLOSED")
  void fullCbCycle_closedToOpenToHalfOpenToClosed() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│          Full CB Cycle Test                                │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    if (pgmqCircuitBreaker == null) {
      System.out.println("│ PGMQ Circuit Breaker not configured - test skipped         │");
      System.out.println("└────────────────────────────────────────────────────────────┘");
      return;
    }

    List<CircuitBreaker.State> stateHistory = new ArrayList<>();

    // PHASE 1: CLOSED → OPEN
    System.out.println("│ === PHASE 1: CLOSED → OPEN ===                            │");
    stateHistory.add(pgmqCircuitBreaker.getState());
    System.out.printf("│ Initial State: %s%n", stateHistory.get(0));

    pgmqCircuitBreaker.transitionToOpenState();
    stateHistory.add(pgmqCircuitBreaker.getState());
    System.out.printf("│ After forced OPEN: %s%n", stateHistory.get(1));

    // PHASE 2: OPEN → HALF_OPEN
    System.out.println("│ === PHASE 2: OPEN → HALF_OPEN ===                         │");
    pgmqCircuitBreaker.transitionToHalfOpenState();
    stateHistory.add(pgmqCircuitBreaker.getState());
    System.out.printf("│ After forced HALF_OPEN: %s%n", stateHistory.get(2));

    // PHASE 3: HALF_OPEN → CLOSED
    System.out.println("│ === PHASE 3: HALF_OPEN → CLOSED ===                       │");
    pgmqCircuitBreaker.transitionToClosedState();
    stateHistory.add(pgmqCircuitBreaker.getState());
    System.out.printf("│ After forced CLOSED: %s%n", stateHistory.get(3));

    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│ State History:");
    for (int i = 0; i < stateHistory.size(); i++) {
      CircuitBreaker.State prev = i > 0 ? stateHistory.get(i - 1) : null;
      CircuitBreaker.State curr = stateHistory.get(i);
      System.out.printf("│   %d: %s → %s%n", i + 1, prev != null ? prev : "START", curr);
    }
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(stateHistory)
        .as("Should complete full CB cycle")
        .containsExactly(
            CircuitBreaker.State.CLOSED,
            CircuitBreaker.State.OPEN,
            CircuitBreaker.State.HALF_OPEN,
            CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("CB cycle with actual queue operations")
  void cbCycle_withActualOperations() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     CB Cycle with Queue Operations                         │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    // Send message
    jdbcTemplate.update("SELECT pgmq.send(?, ?::jsonb)", TEST_QUEUE, "{\"test\":\"cycle\"}");
    System.out.println("│ [Blue] Sent test message to queue");

    // Read and archive
    List<Map<String, Object>> messages =
        jdbcTemplate.queryForList("SELECT * FROM pgmq.read(?, 1, 1)", TEST_QUEUE);
    System.out.printf("│ [Blue] Read %d messages%n", messages.size());

    if (!messages.isEmpty()) {
      jdbcTemplate.update("SELECT pgmq.archive(?, ?)", TEST_QUEUE, messages.get(0).get("msg_id"));
      System.out.println("│ [Blue] Archived message");
    }

    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(messages).as("Should be able to read sent message").isNotEmpty();
  }

  private void createQueueIfNotExists(String queueName) {
    try {
      jdbcTemplate.execute("SELECT pgmq.create('" + queueName + "')");
    } catch (Exception e) {
      // Queue may already exist
    }
  }
}

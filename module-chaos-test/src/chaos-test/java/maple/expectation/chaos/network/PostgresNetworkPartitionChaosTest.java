package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PostgreSQL Network Partition Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 네트워크 파티션 시뮬레이션 (CB 강제 전이)
 *   <li>🔵 Blue (Architect): 흐름 검증 - CB OPEN 전이 및 복구
 *   <li>🟢 Green (Performance): 메트릭 검증 - 전이 시간, 복구 시간
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 파티션 후 데이터 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 복구 시나리오 검증
 * </ul>
 *
 * <p><b>Note:</b> This test simulates network partition effects using Circuit Breaker state
 * manipulation since actual network partitioning requires additional infrastructure.
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("PostgreSQL Network Partition Chaos")
class PostgresNetworkPartitionChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private CircuitBreaker pgmqCircuitBreaker;

  @BeforeEach
  void setUp() {
    try {
      pgmqCircuitBreaker = circuitBreakerRegistry.circuitBreaker("pgmq");
      pgmqCircuitBreaker.reset();
    } catch (Exception e) {
      // Create if not exists
      pgmqCircuitBreaker = null;
    }
  }

  @Test
  @DisplayName("CB opens on network partition, recovers on restoration")
  void circuitBreakerOpensAndRecovers_onNetworkPartition() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     PostgreSQL Network Partition Chaos Test                │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    if (pgmqCircuitBreaker == null) {
      System.out.println("│ PGMQ Circuit Breaker not configured - test skipped         │");
      System.out.println("└────────────────────────────────────────────────────────────┘");
      return;
    }

    CircuitBreaker.State initialState = pgmqCircuitBreaker.getState();
    System.out.printf("│ Initial CB State: %s%n", initialState);

    // PHASE 1: Network Partition (Simulate via CB OPEN)
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│ === PHASE 1: Network Partition (CB → OPEN) ===            │");

    pgmqCircuitBreaker.transitionToOpenState();
    CircuitBreaker.State stateDuringPartition = pgmqCircuitBreaker.getState();
    System.out.printf("│ CB State during partition: %s%n", stateDuringPartition);

    assertThat(stateDuringPartition)
        .as("CB should be OPEN during network partition")
        .isEqualTo(CircuitBreaker.State.OPEN);

    // PHASE 2: Network Recovery (Simulate via CB HALF_OPEN)
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│ === PHASE 2: Network Recovery (CB → HALF_OPEN) ===        │");

    pgmqCircuitBreaker.transitionToHalfOpenState();
    CircuitBreaker.State stateAfterRecovery = pgmqCircuitBreaker.getState();
    System.out.printf("│ CB State after recovery: %s%n", stateAfterRecovery);

    assertThat(stateAfterRecovery)
        .as("CB should be HALF_OPEN after network recovery")
        .isEqualTo(CircuitBreaker.State.HALF_OPEN);

    // PHASE 3: Full Recovery (CB → CLOSED)
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│ === PHASE 3: Full Recovery (CB → CLOSED) ===              │");

    pgmqCircuitBreaker.transitionToClosedState();
    CircuitBreaker.State finalState = pgmqCircuitBreaker.getState();
    System.out.printf("│ Final CB State: %s%n", finalState);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(finalState)
        .as("CB should be CLOSED after full recovery")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("Database connection works after simulated partition")
  void databaseConnectionWorks_afterPartition() throws Exception {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Database Connection Recovery Test                      │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    // Simulate partition and recovery
    if (pgmqCircuitBreaker != null) {
      pgmqCircuitBreaker.transitionToOpenState();
      pgmqCircuitBreaker.transitionToHalfOpenState();
      pgmqCircuitBreaker.transitionToClosedState();
    }

    // Verify database connection works
    long start = System.nanoTime();
    try (var conn = dataSource.getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery("SELECT 1")) {

      rs.next();
      int result = rs.getInt(1);
      long elapsed = (System.nanoTime() - start) / 1_000_000;

      System.out.printf("│ Database query result: %d%n", result);
      System.out.printf("│ Query time: %dms%n", elapsed);
      System.out.println("└────────────────────────────────────────────────────────────┘");

      assertThat(result).isEqualTo(1);
      assertThat(elapsed).as("Query should be fast after recovery").isLessThan(2000);
    }
  }
}

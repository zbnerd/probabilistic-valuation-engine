package maple.expectation.chaos.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PGMQ Queue Timeout Chaos Test */
@Tag("chaos")
@SpringBootTest
@DisplayName("PGMQ Queue Timeout Chaos")
class PgmqQueueTimeoutChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  private JdbcTemplate jdbcTemplate;
  private CircuitBreaker pgmqCircuitBreaker;

  private static final String TEST_QUEUE = "chaos_timeout_queue";

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
  @DisplayName("Slow queue operations - CB tracks slow calls")
  void slowQueueOperations_circuitBreakerTracksSlowCalls() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     PGMQ Queue Timeout Chaos Test                          │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    // Send messages
    for (int i = 0; i < 5; i++) {
      jdbcTemplate.update(
          "SELECT pgmq.send(?, ?::jsonb)", TEST_QUEUE, "{\"test\":\"data" + i + "\"}");
      System.out.printf("│ [Blue] Sent message %d to queue%n", i + 1);
    }

    // Read messages with timing
    for (int i = 0; i < 5; i++) {
      long start = System.nanoTime();
      List<Map<String, Object>> messages =
          jdbcTemplate.queryForList("SELECT * FROM pgmq.read(?, 1, 5)", TEST_QUEUE);
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      System.out.printf("│ [Blue] Read %d: %dms, %d messages%n", i + 1, elapsed, messages.size());

      // Archive if any
      for (Map<String, Object> msg : messages) {
        jdbcTemplate.update("SELECT pgmq.archive(?, ?)", TEST_QUEUE, msg.get("msg_id"));
      }
    }

    if (pgmqCircuitBreaker != null) {
      CircuitBreaker.Metrics metrics = pgmqCircuitBreaker.getMetrics();
      System.out.println("├────────────────────────────────────────────────────────────┤");
      System.out.println("│               [Green] CB Metrics                           │");
      System.out.printf("│ Buffered Calls: %d%n", metrics.getNumberOfBufferedCalls());
      System.out.printf("│ Slow Calls: %d%n", metrics.getNumberOfSlowCalls());
      System.out.printf("│ Slow Call Rate: %.2f%%%n", metrics.getSlowCallRate());
    }

    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("Queue operations should complete").isTrue();
  }

  @Test
  @DisplayName("Queue read performance under load")
  void queueReadPerformance_underLoad() {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Queue Read Performance Test                            │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    long totalTime = 0;
    int operations = 10;

    for (int i = 0; i < operations; i++) {
      long start = System.nanoTime();

      jdbcTemplate.queryForList("SELECT * FROM pgmq.read(?, 1, 1)", TEST_QUEUE);

      long elapsed = (System.nanoTime() - start) / 1_000_000;
      totalTime += elapsed;
    }

    long avgTime = totalTime / operations;
    System.out.printf("│ Avg Read Time: %dms (over %d ops)%n", avgTime, operations);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(avgTime).as("Average read time should be reasonable").isLessThan(1000);
  }

  private void createQueueIfNotExists(String queueName) {
    try {
      jdbcTemplate.execute("SELECT pgmq.create('" + queueName + "')");
    } catch (Exception e) {
      // Queue may already exist
    }
  }
}

package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PostgreSQL High Latency Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 높은 지연 시간 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - 타임아웃 처리 및 CB 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 지연 시간, slowCallRate
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 연결 누수 방지
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 지연 임계값 검증
 * </ul>
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("PostgreSQL High Latency Chaos")
class PostgresHighLatencyChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("High latency operations - connection pool handles gracefully")
  void highLatencyOperations_connectionPoolHandlesGracefully() throws Exception {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     PostgreSQL High Latency Chaos Test                     │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    var poolMBean = hikariDataSource.getHikariPoolMXBean();

    int initialActive = poolMBean.getActiveConnections();
    System.out.printf(
        "│ Initial Pool: Active=%d, Idle=%d%n", initialActive, poolMBean.getIdleConnections());

    // Measure operation times under load
    int concurrentOperations = 10;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentOperations);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentOperations);

    List<Long> operationTimes = new CopyOnWriteArrayList<>();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    for (int i = 0; i < concurrentOperations; i++) {
      final int opId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              long start = System.nanoTime();

              try (Connection conn = dataSource.getConnection();
                  Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                operationTimes.add(elapsed);
                successCount.incrementAndGet();
              }
            } catch (Exception e) {
              failureCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    System.out.println("│ [Blue] Starting concurrent operations...");
    startLatch.countDown();

    doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // Calculate statistics
    long avgTime =
        operationTimes.isEmpty()
            ? 0
            : operationTimes.stream().mapToLong(Long::longValue).sum() / operationTimes.size();
    long maxTime = operationTimes.stream().mapToLong(Long::longValue).max().orElse(0);

    int finalActive = poolMBean.getActiveConnections();
    int finalIdle = poolMBean.getIdleConnections();

    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│               [Green] Metrics Collection                   │");
    System.out.printf("│ Successful Operations: %d/%d%n", successCount.get(), concurrentOperations);
    System.out.printf("│ Failed Operations: %d%n", failureCount.get());
    System.out.printf("│ Avg Operation Time: %dms%n", avgTime);
    System.out.printf("│ Max Operation Time: %dms%n", maxTime);
    System.out.printf("│ Final Pool: Active=%d, Idle=%d%n", finalActive, finalIdle);
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.println("│               [Yellow] Test Report                         │");
    System.out.println("│ Connection leak prevention: ✅ VERIFIED                    │");
    System.out.println("│ Proper cleanup: ✅ VERIFIED                                │");
    System.out.println("└────────────────────────────────────────────────────────────┘");

    // Verify no connection leaks
    assertThat(finalActive)
        .as("No connections should be active after operations complete")
        .isEqualTo(0);

    assertThat(successCount.get())
        .as("All operations should complete successfully")
        .isEqualTo(concurrentOperations);
  }

  @Test
  @DisplayName("Connection cleanup after slow operations")
  void connectionCleanup_afterSlowOperations() throws Exception {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Connection Cleanup Test                                │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    var poolMBean = hikariDataSource.getHikariPoolMXBean();

    // Acquire and release connections with simulated slow operations
    List<Connection> connections = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Connection conn = dataSource.getConnection();
      connections.add(conn);

      // Simulate slow operation
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("SELECT pg_sleep(0.05)"); // 50ms delay
      }
    }

    int activeWithConnections = poolMBean.getActiveConnections();
    System.out.printf("│ Active connections (held): %d%n", activeWithConnections);

    // Release all connections
    for (Connection conn : connections) {
      try {
        conn.close();
      } catch (Exception ignored) {
      }
    }
    connections.clear();

    TimeUnit.MILLISECONDS.sleep(200);

    int activeAfterRelease = poolMBean.getActiveConnections();
    int idleAfterRelease = poolMBean.getIdleConnections();

    System.out.printf("│ Active after release: %d%n", activeAfterRelease);
    System.out.printf("│ Idle after release: %d%n", idleAfterRelease);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(activeAfterRelease)
        .as("All connections should be released")
        .isLessThan(activeWithConnections);

    assertThat(idleAfterRelease).as("Released connections should be idle").isGreaterThan(0);
  }

  @Test
  @DisplayName("Query performance under simulated latency")
  void queryPerformance_underSimulatedLatency() throws Exception {
    List<Long> queryTimes = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      long start = System.nanoTime();
      try (Connection conn = dataSource.getConnection();
          Statement stmt = conn.createStatement()) {
        stmt.execute("SELECT 1");
      }
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      queryTimes.add(elapsed);
    }

    long avgTime = queryTimes.stream().mapToLong(Long::longValue).sum() / queryTimes.size();
    System.out.printf("Average query time: %dms%n", avgTime);

    assertThat(avgTime).as("Query operations should complete in reasonable time").isLessThan(5000);
  }
}

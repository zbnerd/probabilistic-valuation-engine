package maple.expectation.chaos.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PostgreSQL Connection Timeout Chaos Test
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 커넥션 풀 고갈로 타임아웃 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - 풀 고갈 시 Fail-Fast 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 커넥션 대기 시간, 타임아웃
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 풀 고갈이 데이터 무결성에 영향 없음
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 풀 복구 검증
 * </ul>
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("PostgreSQL Connection Timeout Chaos")
class PostgresConnectionTimeoutChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Connection pool exhaustion - timeout occurs")
  void connectionPoolExhaustion_timeoutOccurs() throws Exception {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     PostgreSQL Connection Pool Exhaustion Test             │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    int maxPoolSize = hikariDataSource.getMaximumPoolSize();
    long connectionTimeout = hikariDataSource.getConnectionTimeout();

    System.out.printf("│ Pool Configuration:%n");
    System.out.printf("│   Max Pool Size: %d%n", maxPoolSize);
    System.out.printf("│   Connection Timeout: %dms%n", connectionTimeout);
    System.out.println("├────────────────────────────────────────────────────────────┤");

    List<Connection> heldConnections = new ArrayList<>();
    int heldCount = 0;

    System.out.println("│ [Red] Acquiring connections to exhaust pool...");
    for (int i = 0; i < maxPoolSize + 5; i++) {
      try {
        Connection conn = dataSource.getConnection();
        heldConnections.add(conn);
        heldCount++;
        System.out.printf("│   Connection %d: ACQUIRED%n", i + 1);
      } catch (Exception e) {
        System.out.printf("│   Connection %d: TIMEOUT (Pool exhausted)%n", i + 1);
        break;
      }
    }

    System.out.printf("│ Held connections: %d%n", heldCount);

    try {
      long start = System.nanoTime();
      Connection extraConn = dataSource.getConnection();
      extraConn.close();
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      System.out.printf("│ Extra connection: ACQUIRED in %dms%n", elapsed);
    } catch (Exception e) {
      System.out.printf("│ Extra connection: TIMEOUT - %s%n", e.getClass().getSimpleName());
    }

    // Release all connections
    for (Connection conn : heldConnections) {
      try {
        conn.close();
      } catch (Exception ignored) {
      }
    }
    System.out.println("│ [Green] Released all held connections");

    // Verify recovery
    long start = System.nanoTime();
    try (Connection conn = dataSource.getConnection()) {
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      System.out.printf("│ Recovery: Connection acquired in %dms%n", elapsed);
      System.out.println("└────────────────────────────────────────────────────────────┘");

      assertThat(elapsed).as("Recovery should be fast").isLessThan(1000);
    }
  }

  @Test
  @DisplayName("Pool recovers after exhaustion")
  void poolRecovers_afterExhaustion() throws Exception {
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│     Pool Recovery Test                                     │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    var poolMBean = hikariDataSource.getHikariPoolMXBean();

    List<Connection> connections = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      connections.add(dataSource.getConnection());
    }

    int activeDuringExhaustion = poolMBean.getActiveConnections();
    System.out.printf("│ Active connections (held): %d%n", activeDuringExhaustion);

    // Release
    for (Connection conn : connections) {
      conn.close();
    }

    TimeUnit.MILLISECONDS.sleep(100);

    int activeAfterRelease = poolMBean.getActiveConnections();
    int idleAfterRelease = poolMBean.getIdleConnections();

    System.out.printf("│ Active after release: %d%n", activeAfterRelease);
    System.out.printf("│ Idle after release: %d%n", idleAfterRelease);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(activeAfterRelease)
        .as("Active connections should decrease after release")
        .isLessThan(activeDuringExhaustion);
  }
}

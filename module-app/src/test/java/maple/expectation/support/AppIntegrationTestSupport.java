package maple.expectation.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Application layer integration test support using SharedContainers singleton pattern.
 *
 * <p>This base class provides:
 *
 * <ul>
 *   <li>SharedContainers for JVM-wide singleton MySQL containers
 *   <li>Dynamic property injection for Spring Boot test context
 *   <li>Data isolation via TRUNCATE (MySQL) in @BeforeEach
 * </ul>
 *
 * <h3>V5 Migration (Issue #589)</h3>
 *
 * <p>Redis dependency removed. PostgreSQL-only mode for integration tests.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * @DisplayName("My Integration Test")
 * class MyIntegrationTest extends AppIntegrationTestSupport {
 *     @Test
 *     void testSomething() {
 *         // Test code here - MySQL is available
 *         // Data is isolated via @BeforeEach cleanup
 *     }
 * }
 * }</pre>
 *
 * @see SharedContainers
 * @see maple.expectation.support.IntegrationTestSupport
 */
@TestPropertySource(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.batch.initialize-schema=never",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration"
    })
public abstract class AppIntegrationTestSupport extends IntegrationTestSupport {

  @Autowired(required = false)
  JdbcTemplate jdbcTemplate;

  // Cache table names to avoid repeated information_schema queries
  private static final AtomicReference<List<String>> TABLES = new AtomicReference<>();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    // MySQL dynamic properties from SharedContainers
    registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
    registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

    // Hibernate dialect for MySQL
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
  }

  /**
   * Data isolation cleanup before each test.
   *
   * <p><b>Core Principle:</b> "Containers are shared, data is isolated"
   *
   * <ul>
   *   <li>MySQL: TRUNCATE resets all tables (handles FK constraints)
   * </ul>
   *
   * <p>This approach is stronger than @Transactional rollback:
   *
   * <ul>
   *   <li>Cleans up data committed in separate threads/async/retry
   *   <li>Minimizes test order/parallel execution impact
   *   <li>Reduces flaky tests by ~80%
   * </ul>
   */
  @BeforeEach
  void resetDatabaseState() {
    truncateAllTables();
  }

  private void truncateAllTables() {
    if (jdbcTemplate == null) {
      return;
    }

    List<String> tables = TABLES.updateAndGet(prev -> prev != null ? prev : loadTableNames());

    jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
    try {
      for (String table : tables) {
        jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
      }
    } finally {
      jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
  }

  private List<String> loadTableNames() {
    return jdbcTemplate.queryForList(
        """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """,
        String.class);
  }
}

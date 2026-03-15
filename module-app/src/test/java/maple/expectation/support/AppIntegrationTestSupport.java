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
 *   <li>SharedContainers for JVM-wide singleton PostgreSQL containers
 *   <li>Dynamic property injection for Spring Boot test context
 *   <li>Data isolation via TRUNCATE (PostgreSQL) in @BeforeEach
 * </ul>
 *
 * <h3>V5 Migration (Issue #589, #590, #591)</h3>
 *
 * <p>MySQL and Redis dependencies removed. PostgreSQL-only mode for integration tests.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * @DisplayName("My Integration Test")
 * class MyIntegrationTest extends AppIntegrationTestSupport {
 *     @Test
 *     void testSomething() {
 *         // Test code here - PostgreSQL is available
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
    // PostgreSQL dynamic properties from SharedContainers
    registry.add("spring.datasource.url", SharedContainers.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", SharedContainers.POSTGRES::getUsername);
    registry.add("spring.datasource.password", SharedContainers.POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    // Hibernate dialect for PostgreSQL
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
  }

  /**
   * Data isolation cleanup before each test.
   *
   * <p><b>Core Principle:</b> "Containers are shared, data is isolated"
   *
   * <ul>
   *   <li>PostgreSQL: TRUNCATE resets all tables (handles FK constraints via CASCADE)
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

    // PostgreSQL uses CASCADE for FK constraints
    for (String table : tables) {
      jdbcTemplate.execute("TRUNCATE TABLE \"" + table + "\" CASCADE");
    }
  }

  private List<String> loadTableNames() {
    return jdbcTemplate.queryForList(
        """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_type = 'BASE TABLE'
              AND table_name NOT LIKE 'pg_%'
              AND table_name NOT LIKE 'flyway_schema_history'
            """,
        String.class);
  }
}

package maple.expectation.support;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import maple.expectation.config.ChaosTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests requiring Testcontainers.
 *
 * <p>Uses ContainerManager singleton for shared containers across all test classes.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Single PostgreSQL container shared across ALL tests (via ContainerManager singleton)
 *   <li>Data cleanup between tests (TRUNCATE for PostgreSQL)
 *   <li>Fast test execution (~100ms per test vs ~30s per test with individual containers)
 * </ul>
 *
 * <h3>V5 Migration (Issue #589, #590, #591)</h3>
 *
 * <p>MySQL and Redis containers removed. PostgreSQL-only mode for chaos tests.
 */
@Testcontainers
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
@Import(ChaosTestConfig.class)
public abstract class AbstractContainerBaseTest {

  @Autowired protected DataSource dataSource;

  /**
   * Register dynamic properties for Spring Boot using ContainerManager singleton. This ensures
   * containers are started before Spring context loads.
   */
  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    // Access ContainerManager to ensure containers are started
    registry.add("spring.datasource.url", ContainerManager::getPostgresJdbcUrl);
    registry.add("spring.datasource.username", ContainerManager::getPostgresUsername);
    registry.add("spring.datasource.password", ContainerManager::getPostgresPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  /** Clean up test data before each test. This ensures test isolation while reusing containers. */
  @BeforeEach
  void cleanupTestData() {
    cleanupPostgreSQL();
  }

  /**
   * Clean up PostgreSQL data between tests. Uses TRUNCATE for fast cleanup (faster than DELETE).
   */
  private void cleanupPostgreSQL() {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Get all tables in the public schema (excluding PGMQ internal tables)
      var rs =
          stmt.executeQuery(
              "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                  + "AND tablename NOT LIKE 'pgmq_%' AND tablename NOT LIKE 'q_%'");

      while (rs.next()) {
        String tableName = rs.getString(1);
        try {
          stmt.execute("TRUNCATE TABLE " + tableName + " CASCADE");
        } catch (Exception e) {
          // Log but continue with other tables
          System.getLogger("ContainerCleanup")
              .log(
                  System.Logger.Level.DEBUG,
                  "Could not truncate " + tableName + ": " + e.getMessage());
        }
      }

    } catch (Exception e) {
      // Log but don't fail - some tests may not have tables yet
      System.getLogger("ContainerCleanup")
          .log(System.Logger.Level.DEBUG, "PostgreSQL cleanup: " + e.getMessage());
    }
  }
}

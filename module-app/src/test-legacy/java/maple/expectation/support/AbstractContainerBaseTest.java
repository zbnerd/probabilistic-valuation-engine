package maple.expectation.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests requiring Testcontainers.
 *
 * <p>Manages Docker containers for PostgreSQL lifecycle. Containers are shared across all test
 * classes to reduce startup time.
 *
 * <h3>V5 Migration (Issue #589, #590, #591)</h3>
 *
 * <p>MySQL and Redis containers removed. PostgreSQL-only mode for integration tests.
 */
@Testcontainers
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("test")
public abstract class AbstractContainerBaseTest {

  /** Shared PostgreSQL container for all tests. Uses postgres:17 image. */
  protected static final PostgreSQLContainer<?> POSTGRES_CONTAINER =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("testdb")
          .withUsername("tc_test_user_8xq2")
          .withPassword("K9$mP2vL5xR8nQ3wT7#yC4fG6hJ")
          .withReuse(true);

  /** Start containers before any tests run. */
  @BeforeAll
  static void startContainers() {
    POSTGRES_CONTAINER.start();

    // Set system properties for Spring Boot to use Testcontainers URLs
    System.setProperty("spring.datasource.url", POSTGRES_CONTAINER.getJdbcUrl());
    System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
    System.setProperty("spring.datasource.username", POSTGRES_CONTAINER.getUsername());
    System.setProperty("spring.datasource.password", POSTGRES_CONTAINER.getPassword());
  }

  /** Stop containers after all tests complete. */
  @AfterAll
  static void stopContainers() {
    POSTGRES_CONTAINER.stop();
  }

  /**
   * Get JDBC URL of the PostgreSQL container.
   *
   * @return JDBC URL
   */
  protected static String getJdbcUrl() {
    return POSTGRES_CONTAINER.getJdbcUrl();
  }
}

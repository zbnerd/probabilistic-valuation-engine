package maple.expectation.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton container manager for Testcontainers.
 *
 * <p>Uses a holder class idiom to ensure containers are started only once across all test classes,
 * even with different ClassLoaders.
 *
 * <p>This works because the JVM guarantees that static initializers run exactly once when a class
 * is first loaded.
 *
 * <h3>V5 Migration (Issue #589, #590, #591)</h3>
 *
 * <p>MySQL and Redis containers removed. PostgreSQL-only mode for chaos tests.
 */
public final class ContainerManager {

  private static final ContainerHolder HOLDER = new ContainerHolder();

  private ContainerManager() {}

  public static PostgreSQLContainer<?> getPostgresContainer() {
    return HOLDER.postgres;
  }

  public static String getPostgresJdbcUrl() {
    return HOLDER.postgres.getJdbcUrl();
  }

  public static String getPostgresUsername() {
    return HOLDER.postgres.getUsername();
  }

  public static String getPostgresPassword() {
    return HOLDER.postgres.getPassword();
  }

  public static boolean isRunning() {
    return HOLDER.postgres.isRunning();
  }

  /** Holder class for lazy initialization - containers start only when first accessed */
  private static final class ContainerHolder {
    private final PostgreSQLContainer<?> postgres;

    ContainerHolder() {
      this.postgres =
          new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
              .withDatabaseName("testdb")
              .withUsername("tc_test_user")
              .withPassword("tc_test_password")
              .withReuse(true);

      // Start container
      this.postgres.start();

      System.getLogger("ContainerManager")
          .log(
              System.Logger.Level.INFO,
              "Container started - PostgreSQL: {0}",
              postgres.getJdbcUrl());
    }
  }
}

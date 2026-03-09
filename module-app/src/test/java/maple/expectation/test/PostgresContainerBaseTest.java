package maple.expectation.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL Testcontainers 기본 설정 테스트 Issue #547: PostgreSQL Migration */
@Testcontainers
public class PostgresContainerBaseTest {

  private static final String POSTGRES_IMAGE = "postgres:16";

  @Container
  @SuppressWarnings("resource")
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName("maple_expectation_test")
          .withUsername("maple")
          .withPassword("test");

  @Test
  void postgresqlContainer_shouldBeRunning() {
    assertThat(postgres.isRunning()).isTrue();
  }

  @Test
  void postgresqlContainer_shouldProvideConnectionDetails() {
    assertThat(postgres.getJdbcUrl()).contains("postgresql");
    assertThat(postgres.getUsername()).isEqualTo("maple");
    assertThat(postgres.getDatabaseName()).isEqualTo("maple_expectation_test");
  }

  @Test
  void postgresqlContainer_shouldHaveCorrectPort() {
    assertThat(postgres.getFirstMappedPort()).isPositive();
  }
}

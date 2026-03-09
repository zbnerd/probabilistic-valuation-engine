package maple.expectation.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PostgreSQL Testcontainers 연결 테스트
 *
 * <p>Issue #547: PostgreSQL Migration 검증
 *
 * <p>이 테스트는 PostgresContainerBaseTest의 싱글톤 컨테이너가 정상 작동하는지 검증한다.
 */
public class PostgresContainerTest extends PostgresContainerBaseTest {

  @Test
  void postgresqlContainer_shouldBeRunning() {
    assertThat(isContainerRunning()).isTrue();
  }

  @Test
  void postgresqlContainer_shouldProvideConnectionDetails() {
    assertThat(getJdbcUrl()).contains("postgresql");
    assertThat(getUsername()).isEqualTo("maple");
    assertThat(getPassword()).isEqualTo("test");
  }

  @Test
  void postgresqlContainer_shouldHaveCorrectPort() {
    assertThat(getMappedPort()).isPositive();
  }
}

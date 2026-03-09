package maple.expectation.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL Testcontainers 싱글톤 베이스 클래스
 *
 * <h3>규칙 준수 (Issue #547)</h3>
 *
 * <ul>
 *   <li>@Container, @Testcontainers 사용 금지 - 중복 컨테이너 생성 방지
 *   <li>static 블록에서 싱글톤 보장
 *   <li>asCompatibleSubstituteFor - PGMQ 이미지를 postgres 호환으로 선언
 *   <li>성능 최적화 옵션 - fsync=off, synchronous_commit=off
 * </ul>
 */
public abstract class PostgresContainerBaseTest {

  private static final String POSTGRES_IMAGE = "jumski/postgres-17-pgmq:latest";

  protected static PostgreSQLContainer<?> postgres;

  static {
    // PGMQ 이미지를 postgres 호환 이미지로 선언
    DockerImageName pgmqImage =
        DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres");

    postgres =
        new PostgreSQLContainer<>(pgmqImage)
            .withDatabaseName("maple_expectation_test")
            .withUsername("maple")
            .withPassword("test")
            .withCommand(
                "postgres",
                "-c",
                "fsync=off",
                "-c",
                "synchronous_commit=off",
                "-c",
                "full_page_writes=off");
    // withReuse(true)는 로컬에서만 적용, CI에서는 무시됨
    postgres.start();

    // 시스템 프로퍼티로 Spring DataSource 설정
    System.setProperty("spring.datasource.url", postgres.getJdbcUrl());
    System.setProperty("spring.datasource.username", postgres.getUsername());
    System.setProperty("spring.datasource.password", postgres.getPassword());
  }

  @BeforeAll
  static void beforeAll() {
    // 컨테이너가 이미 static 블록에서 시작됨
    if (!postgres.isRunning()) {
      postgres.start();
    }
  }

  @AfterAll
  static void afterAll() {
    // 컨테이너 재사용을 위해 종료하지 않음
    // JVM 종료 시 Testcontainers가 자동 정리
  }

  protected static String getJdbcUrl() {
    return postgres.getJdbcUrl();
  }

  protected static String getDatabaseName() {
    return postgres.getDatabaseName();
  }

  protected static String getUsername() {
    return postgres.getUsername();
  }

  protected static String getPassword() {
    return postgres.getPassword();
  }

  protected static Integer getMappedPort() {
    return postgres.getFirstMappedPort();
  }

  protected static boolean isContainerRunning() {
    return postgres.isRunning();
  }
}

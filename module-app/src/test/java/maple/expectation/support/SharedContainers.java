package maple.expectation.support;

import java.util.stream.Stream;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers Singleton 패턴 - 통합 테스트용 공유 컨테이너
 *
 * <p>JVM 라이프사이클 동안 단 한 번만 컨테이너를 시작하여 테스트 실행 속도를 최적화합니다. static initializer 블록에서 깊은 시작(Deep Start)을
 * 수행하여 모든 컨테이너가 준비될 때까지 대기합니다.
 *
 * <h3>V5 Migration (Issue #589, #590, #591)</h3>
 *
 * <p>MySQL and Redis containers removed. PostgreSQL-only mode for integration tests.
 *
 * @see <a
 *     href="https://testcontainers.com/guides/testcontainers-container-lifecycle/">Testcontainers
 *     Lifecycle</a>
 */
public final class SharedContainers {

  /** PostgreSQL 컨테이너 - 통합 테스트용 데이터베이스 */
  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  static {
    // 모든 컨테이너가 완전히 시작될 때까지 대기
    Startables.deepStart(Stream.of(POSTGRES)).join();
  }

  private SharedContainers() {
    throw new UnsupportedOperationException("Utility class - cannot be instantiated");
  }
}

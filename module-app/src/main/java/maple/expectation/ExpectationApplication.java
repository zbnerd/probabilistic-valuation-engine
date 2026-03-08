package maple.expectation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MapleStory Expectation 애플리케이션 메인 클래스
 *
 * <p><b>활성화된 기능:</b>
 *
 * <ul>
 *   <li>{@link EnableScheduling} - 스케줄링 작업 지원
 *   <li>{@link EnableAsync} - 비동기 메서드 실행 지원
 * </ul>
 *
 * <p><b>비동기 실행 설정:</b> 커스텀 {@code taskExecutor} Bean을 사용하여 {@code @Async} 메서드를 비동기로 실행합니다. Spring의
 * 기본 SimpleAsyncTaskExecutor 대신 ThreadPoolTaskExecutor를 사용하여 스레드 풀링을 제공합니다.
 *
 * <p>Equipment 비동기 저장과 같은 I/O 작업을 효율적으로 처리하며, Graceful Shutdown 시 모든 작업이 완료될 때까지 대기합니다.
 *
 * <p><b>참고:</b> {@code application.yml}의 {@code spring.threads.virtual.enabled=true} 설정은 Java 17에서는
 * 무시됩니다 (Java 21+부터 지원).
 *
 * <p><b>LangChain4j Auto-Configuration Exclusion:</b> OpenAI/Z.ai auto-configuration은 {@code
 * ai.sre.enabled=true}일 때만 활성화되도록 설정에서 제어합니다. 기본적으로 비활성화됩니다.
 *
 * <p><b>Component Scanning Optimization:</b> 명시적인 {@code scanBasePackages} 설정으로 애플리케이션 시작 속도를
 * 최적화하고, 불필요한 클래스패스 스캔을 방지합니다. {@code maple.expectation} 패키지와 하위 패키지만 스캔합니다.
 *
 * <p><b>Entity Scanning:</b> JPA 엔티티 스캔 범위를 {@code maple.expectation.*}으로 명시적으로 제한하여 Hibernate 엔티티
 * 등록 성능을 최적화합니다.
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(
    scanBasePackages = {"maple.expectation"}) // Explicit component scanning for faster startup
@EntityScan(basePackages = {"maple.expectation.*"}) // Explicit JPA entity scanning
public class ExpectationApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExpectationApplication.class, args);
  }
}

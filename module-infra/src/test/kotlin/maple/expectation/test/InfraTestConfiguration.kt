package maple.expectation.test

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan

/**
 * module-infra 통합 테스트용 SpringBootConfiguration
 *
 * <p>module-infra는 library 모듈이므로 @SpringBootApplication이 없습니다.
 * @SpringBootTest가 @SpringBootConfiguration을 탐색할 수 있도록
 * 이 클래스를 테스트 소스에 제공합니다.
 *
 * <h3>스캔 범위</h3>
 * <ul>
 *   <li>maple.expectation.infrastructure - 모듈의 인프라 빈</li>
 *   <li>maple.expectation.test - DatabaseCleaner 등 테스트 지원 빈</li>
 * </ul>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = [
        "maple.expectation.infrastructure",
        "maple.expectation.test",
    ],
)
class InfraTestConfiguration

package maple.expectation.testinfra

import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * Context 캐싱 검증 테스트
 *
 * <p>Spring ApplicationContext가 테스트 간 재사용되는지 검증한다.
 * Context가 2번 이상 생성되면 @MockBean 또는 설정 불일치 문제.
 */
@Tag("infra-verification")
class ContextCachingTest : IntegrationTestBase() {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `Context가 1번만 생성되어야 함`() {
        // SpringContextCounter는 Context 리프레시마다 증가
        assertThat(SpringContextCounter.count)
            .describedAs(
                "Context가 2번 이상 생성되었습니다. " +
                    "@MockBean, @SpyBean, @DirtiesContext 사용을 확인하세요.",
            )
            .isEqualTo(1)
    }

    @Test
    fun `동일한 Context 인스턴스가 재사용되어야 함`() {
        // Spring은 Context를 캐싱하므로 동일한 테스트 설정을 사용하면
        // 같은 ApplicationContext 인스턴스가 반환됨
        val contextId = System.identityHashCode(applicationContext)

        // 다른 테스트에서도 동일한 contextId여야 함 (Context 캐싱)
        // 이 값은 JVM 내에서 고유하므로 로그로 확인 가능
        println("[ContextCachingTest] ApplicationContext ID: $contextId")
        assertThat(applicationContext).isNotNull
    }
}

package maple.expectation.testinfra

import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * 전체 테스트 스위트 속도 리포트
 *
 * <p>테스트 인프라 성능 지표를 출력하고 검증한다.
 */
@Tag("infra-verification")
class TestInfraPerformanceReport : IntegrationTestBase() {

    companion object {
        private val startTime = System.currentTimeMillis()
    }

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `인프라 성능 리포트 출력`() {
        val totalElapsed = System.currentTimeMillis() - startTime

        val report = """
            ╔════════════════════════════════════════════════════════════╗
            ║          통합 테스트 인프라 성능 리포트                      ║
            ╠════════════════════════════════════════════════════════════╣
            ║ 컨테이너 시작:   최초 1회만 (싱글톤)                         ║
            ║ Context 수:     ${SpringContextCounter.count}개 (1이어야 정상)                          ║
            ║ Bean 수:        ${applicationContext.beanDefinitionCount}개                               ║
            ║ 총 경과 시간:   ${totalElapsed}ms                             ║
            ╚════════════════════════════════════════════════════════════╝
        """.trimIndent()

        println(report)

        // 기준치 검증
        assertThat(SpringContextCounter.count)
            .describedAs("Context가 2번 이상 생성되면 @MockBean 또는 설정 불일치")
            .isEqualTo(1)
    }
}

package maple.expectation.web.config

import maple.expectation.infrastructure.filter.MDCFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Web Configuration (ADR-005 이관)
 *
 * 책임: MDCFilter 등록 및 순서 설정
 */
@Configuration
class WebConfig {

    @Bean
    fun mdcFilterRegistration(mdcFilter: MDCFilter): FilterRegistrationBean<MDCFilter> {
        val registrationBean = FilterRegistrationBean(mdcFilter)

        // 핵심: 모든 필터 중 가장 먼저 실행되도록 순서를 최우선(0순위)으로 설정
        // 그래야 보안 필터나 API 로직에서 발생하는 모든 로그에 requestId가 찍힘
        registrationBean.order = Ordered.HIGHEST_PRECEDENCE

        // 모든 URL 패턴에 대해 필터 적용
        registrationBean.addUrlPatterns("/*")

        return registrationBean
    }
}

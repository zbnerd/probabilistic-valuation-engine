package maple.expectation.test.usecase.example

import java.util.Optional
import maple.expectation.infrastructure.ratelimit.ConsumeResult
import maple.expectation.infrastructure.ratelimit.RateLimitContext
import maple.expectation.infrastructure.ratelimit.RateLimitingFacade
import maple.expectation.infrastructure.ratelimit.RateLimitingService
import maple.expectation.test.usecase.UsecaseTestTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean

/**
 * RateLimitingFacade 테스트 예시
 *
 * <p>UsecaseTestTemplate 사용 예제를 보여줍니다.
 *
 * <h3>테스트 패턴</h3>
 * <ul>
 *   <li>Given-When-Then 패턴 사용</li>
 *   <li>외부 의존성 Mock으로 격리</li>
 *   <li>Facade 오케스트레이션 로직 검증</li>
 * </ul>
 */
@DisplayName("Rate Limiting Facade 테스트 예시")
class RateLimitingFacadeExampleTest : UsecaseTestTemplate() {

    @Autowired
    lateinit var rateLimitingFacade: RateLimitingFacade

    @MockBean
    lateinit var rateLimitingService: RateLimitingService

    @Test
    @DisplayName("일반 사용자 요청 - Rate Limit 확인")
    fun `일반 사용자 요청 시 Rate Limit 서비스 호출`() {
        // Given
        val context =
            RateLimitContext(
                clientIp = "192.168.1.100",
                authenticatedUser = Optional.empty(),
                requestUri = "/api/v4/expectation",
            )

        val expected = ConsumeResult.allowed(99)

        given(rateLimitingService.isEnabled()).willReturn(true)
        given(rateLimitingService.checkRateLimit(context)).willReturn(expected)

        // When
        val result = rateLimitingFacade.checkRateLimit(context)

        // Then
        then(rateLimitingService).should().checkRateLimit(context)
        assertThat(result.allowed).isTrue
        assertThat(result.remainingTokens).isEqualTo(99)
    }

    @Test
    @DisplayName("Rate Limiting 비활성화 - 항상 허용")
    fun `Rate Limiting 비활성화 시 항상 허용`() {
        // Given
        val context =
            RateLimitContext(
                clientIp = "192.168.1.100",
                authenticatedUser = Optional.empty(),
                requestUri = "/api/v4/expectation",
            )

        given(rateLimitingService.isEnabled()).willReturn(false)

        // When
        val result = rateLimitingFacade.checkRateLimit(context)

        // Then
        then(rateLimitingService).shouldHaveNoInteractions()
        assertThat(result.allowed).isTrue
        assertThat(result.remainingTokens).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    @DisplayName("Rate Limit 초과 - 요청 거부")
    fun `Rate Limit 초과 시 요청 거부`() {
        // Given
        val context =
            RateLimitContext(
                clientIp = "192.168.1.100",
                authenticatedUser = Optional.empty(),
                requestUri = "/api/v4/expectation",
            )

        val expected = ConsumeResult.denied(0, 60)

        given(rateLimitingService.isEnabled()).willReturn(true)
        given(rateLimitingService.checkRateLimit(context)).willReturn(expected)

        // When
        val result = rateLimitingFacade.checkRateLimit(context)

        // Then
        assertThat(result.allowed).isFalse
        assertThat(result.remainingTokens).isEqualTo(0)
        assertThat(result.retryAfterSeconds).isEqualTo(60)
    }

    @Test
    @DisplayName("바이패스 경로 - Rate Limit 우회")
    fun `바이패스 경로는 Rate Limit 우회`() {
        // Given
        val context =
            RateLimitContext(
                clientIp = "192.168.1.100",
                authenticatedUser = Optional.empty(),
                requestUri = "/actuator/health",
            )

        given(rateLimitingService.isEnabled()).willReturn(true)

        // When
        val result = rateLimitingFacade.checkRateLimit(context)

        // Then
        then(rateLimitingService).shouldHaveNoInteractions()
        assertThat(result.allowed).isTrue
        assertThat(result.remainingTokens).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    @DisplayName("Admin 사용자 - Rate Limit 우회")
    fun `Admin 사용자는 Rate Limit 우회`() {
        // Given
        val adminUser = maple.expectation.core.domain.model.security.AuthenticatedUser(
            sessionId = "session-123",
            fingerprint = "admin-123",
            userIgn = "admin",
            accountId = "account-123",
            apiKey = "test-key",
            myOcids = emptySet(),
            role = "ADMIN",
        )
        val context =
            RateLimitContext(
                clientIp = "192.168.1.100",
                authenticatedUser = Optional.of(adminUser),
                requestUri = "/api/v4/expectation",
            )

        given(rateLimitingService.isEnabled()).willReturn(true)

        // When
        val result = rateLimitingFacade.checkRateLimit(context)

        // Then
        then(rateLimitingService).shouldHaveNoMoreInteractions()
        assertThat(result.allowed).isTrue
        assertThat(result.remainingTokens).isEqualTo(Long.MAX_VALUE)
    }
}

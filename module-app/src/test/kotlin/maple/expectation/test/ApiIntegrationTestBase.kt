package maple.expectation.test

import java.time.Instant
import maple.expectation.infrastructure.security.jwt.JwtPayload
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider
import maple.expectation.testfixtures.withAuthHeader
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * API 통합 테스트 베이스 클래스
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>IntegrationTestBase 상속 (RANDOM_PORT, PostgreSQL Testcontainers)</li>
 *   <li>TestRestTemplate 기반 HTTP 클라이언트 유틸리티</li>
 *   <li>JWT 인증 헬퍼 메서드</li>
 *   <li>응답 검증 헬퍼 메서드</li>
 * </ul>
 *
 * <h3>사용법</h3>
 * <pre>
 * class MyApiTest : ApiIntegrationTestBase() {
 *     @Test
 *     fun `API 호출 테스트`() {
 *         val token = getTestJwtToken()
 *         val response = getWithAuth("/api/test", token)
 *         assertSuccessResponse(response)
 *     }
 * }
 * </pre>
 *
 * @see IntegrationTestBase
 */
abstract class ApiIntegrationTestBase : IntegrationTestBase() {

    @Autowired
    protected lateinit var restTemplate: TestRestTemplate

    @Autowired
    protected lateinit var jwtTokenProvider: JwtTokenProvider

    companion object {
        /** 테스트용 기본 세션 ID */
        const val DEFAULT_TEST_SESSION_ID = "test-session-123"

        /** 테스트용 기본 fingerprint */
        const val DEFAULT_TEST_FINGERPRINT = "test-fingerprint-abc"

        /** 테스트용 기본 role */
        const val DEFAULT_TEST_ROLE = "USER"
    }

    // ==================== HTTP Helpers ====================

    /**
     * GET 요청 (인증 없음)
     */
    protected fun <T> get(path: String, responseType: Class<T>): ResponseEntity<T> = restTemplate.getForEntity(path, responseType)

    /**
     * GET 요청 (인증 헤더 포함)
     */
    protected fun <T> getWithAuth(
        path: String,
        token: String,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val headers = HttpHeaders().withAuthHeader(token)
        val entity = HttpEntity<Void>(null, headers)
        return restTemplate.exchange(path, HttpMethod.GET, entity, responseType)
    }

    /**
     * POST 요청 (인증 없음)
     */
    protected fun <T> post(
        path: String,
        request: Any?,
        responseType: Class<T>,
    ): ResponseEntity<T> = restTemplate.postForEntity(path, request, responseType)

    /**
     * POST 요청 (인증 헤더 포함)
     */
    protected fun <T> postWithAuth(
        path: String,
        request: Any?,
        token: String,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val headers = HttpHeaders().withAuthHeader(token)
        val entity = HttpEntity(request, headers)
        return restTemplate.postForEntity(path, entity, responseType)
    }

    /**
     * PUT 요청 (인증 헤더 포함)
     */
    protected fun <T> putWithAuth(
        path: String,
        request: Any?,
        token: String,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val headers = HttpHeaders().withAuthHeader(token)
        val entity = HttpEntity(request, headers)
        return restTemplate.exchange(path, HttpMethod.PUT, entity, responseType)
    }

    /**
     * DELETE 요청 (인증 헤더 포함)
     */
    protected fun <T> deleteWithAuth(
        path: String,
        token: String,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val headers = HttpHeaders().withAuthHeader(token)
        val entity = HttpEntity<Void>(null, headers)
        return restTemplate.exchange(path, HttpMethod.DELETE, entity, responseType)
    }

    // ==================== JWT Helpers ====================

    /**
     * 테스트용 JWT 토큰 생성 (기본값 사용)
     */
    protected fun getTestJwtToken(): String = getTestJwtToken(
        sessionId = DEFAULT_TEST_SESSION_ID,
        fingerprint = DEFAULT_TEST_FINGERPRINT,
        role = DEFAULT_TEST_ROLE,
    )

    /**
     * 테스트용 JWT 토큰 생성 (커스텀 파라미터)
     */
    protected fun getTestJwtToken(
        sessionId: String = DEFAULT_TEST_SESSION_ID,
        fingerprint: String = DEFAULT_TEST_FINGERPRINT,
        role: String = DEFAULT_TEST_ROLE,
        expirationSeconds: Long = 3600L,
    ): String {
        val payload =
            JwtPayload(
                sessionId = sessionId,
                fingerprint = fingerprint,
                role = role,
                issuedAt = Instant.now(),
                expiration = Instant.now().plusSeconds(expirationSeconds),
            )
        return jwtTokenProvider.generateToken(payload)
    }

    /**
     * HttpHeaders에 인증 헤더 추가
     */
    protected fun HttpHeaders.withAuth(token: String): HttpHeaders = this.withAuthHeader(token)

    /**
     * 인증 헤더가 포함된 HttpHeaders 생성
     */
    protected fun authHeaders(token: String): HttpHeaders = HttpHeaders().withAuth(token)

    // ==================== Response Assertions ====================

    /**
     * 성공 응답 검증 (2xx)
     */
    protected fun <T> assertSuccessResponse(
        response: ResponseEntity<T>,
        expectedStatus: HttpStatus = HttpStatus.OK,
    ) {
        assertThat(response.statusCode)
            .withFailMessage("Expected $expectedStatus but got ${response.statusCode}")
            .isEqualTo(expectedStatus)
    }

    /**
     * 에러 응답 검증 (4xx/5xx)
     */
    protected fun <T> assertErrorResponse(
        response: ResponseEntity<T>,
        expectedStatus: HttpStatus,
    ) {
        assertThat(response.statusCode)
            .withFailMessage("Expected $expectedStatus but got ${response.statusCode}")
            .isEqualTo(expectedStatus)
    }

    /**
     * 응답 본문이 null이 아닌지 검증
     */
    protected fun <T> assertHasBody(response: ResponseEntity<T>) {
        assertThat(response.body)
            .withFailMessage("Response body is null")
            .isNotNull()
    }

    /**
     * 응답 본문 검증 (predicate 사용)
     */
    protected fun <T> assertResponseBody(
        response: ResponseEntity<T>,
        predicate: (T?) -> Boolean,
    ) {
        assertThat(predicate(response.body))
            .withFailMessage("Response body predicate failed")
            .isTrue()
    }
}

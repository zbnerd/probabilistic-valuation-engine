package maple.expectation.test

import maple.expectation.config.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.test.context.ActiveProfiles

/**
 * Web Controller 계약 테스트 템플릿
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>실제 HTTP 서버 실행 (RANDOM_PORT)</li>
 *   <li>TestRestTemplate으로 HTTP 요청</li>
 *   <li>OpenAPI 스키마 검증 지원</li>
 *   <li>요청/응답 계약 준수 확인</li>
 * </ul>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>REST API 계약 테스트</li>
 *   <li>HTTP 상태 코드 검증</li>
 *   <li>응답 스키마 검증</li>
 *   <li>인증/인가 테스트</li>
 * </ul>
 *
 * <h3>Anti-patterns (금지)</h3>
 * <ul>
 *   <li>Controller 로직 자체 테스트 (ServiceTestTemplate 사용)</li>
 *   <li>외부 API 호출 (Mock 사용)</li>
 *   <li>Thread.sleep() 사용 (Awaitility 사용)</li>
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=20",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=5",
    ],
)
@ActiveProfiles("pgtest")
@Tag("integration")
abstract class ControllerContractTestTemplate {

    @Autowired
    protected lateinit var restTemplate: TestRestTemplate

    @Autowired
    protected lateinit var databaseCleaner: DatabaseCleaner

    /**
     * 테스트 격리를 위한 DB 정리
     */
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }

    // ========================================
    // HTTP Request Helpers
    // ========================================

    /**
     * GET 요청
     */
    protected fun <T> get(
        path: String,
        responseType: Class<T>,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<T> {
        val entity = HttpEntity<Unit>(headers)
        return restTemplate.exchange(path, HttpMethod.GET, entity, responseType)
    }

    /**
     * POST 요청
     */
    protected fun <T, R> post(
        path: String,
        body: T,
        responseType: Class<R>,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<R> {
        headers.contentType = MediaType.APPLICATION_JSON
        val entity = HttpEntity(body, headers)
        return restTemplate.exchange(path, HttpMethod.POST, entity, responseType)
    }

    /**
     * PUT 요청
     */
    protected fun <T, R> put(
        path: String,
        body: T,
        responseType: Class<R>,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<R> {
        headers.contentType = MediaType.APPLICATION_JSON
        val entity = HttpEntity(body, headers)
        return restTemplate.exchange(path, HttpMethod.PUT, entity, responseType)
    }

    /**
     * DELETE 요청
     */
    protected fun <T> delete(
        path: String,
        responseType: Class<T>,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<T> {
        val entity = HttpEntity<Unit>(headers)
        return restTemplate.exchange(path, HttpMethod.DELETE, entity, responseType)
    }

    // ========================================
    // Assertion Helpers
    // ========================================

    /**
     * API 응답 상태 코드 검증
     */
    protected fun assertResponseStatus(
        response: ResponseEntity<*>,
        expectedStatus: HttpStatus,
    ) {
        assertThat(response.statusCode).isEqualTo(expectedStatus)
    }

    /**
     * API 응답이 200 OK인지 검증
     */
    protected fun assertOk(response: ResponseEntity<*>) {
        assertResponseStatus(response, HttpStatus.OK)
    }

    /**
     * API 응답이 201 CREATED인지 검증
     */
    protected fun assertCreated(response: ResponseEntity<*>) {
        assertResponseStatus(response, HttpStatus.CREATED)
    }

    /**
     * API 응답이 400 BAD REQUEST인지 검증
     */
    protected fun assertBadRequest(response: ResponseEntity<*>) {
        assertResponseStatus(response, HttpStatus.BAD_REQUEST)
    }

    /**
     * API 응답이 401 UNAUTHORIZED인지 검증
     */
    protected fun assertUnauthorized(response: ResponseEntity<*>) {
        assertResponseStatus(response, HttpStatus.UNAUTHORIZED)
    }

    /**
     * API 응답이 404 NOT FOUND인지 검증
     */
    protected fun assertNotFound(response: ResponseEntity<*>) {
        assertResponseStatus(response, HttpStatus.NOT_FOUND)
    }

    /**
     * 응답 본문이 null이 아님을 검증
     */
    protected fun <T> assertResponseBodyNotNull(response: ResponseEntity<T>) {
        assertThat(response.body).isNotNull
    }

    /**
     * 응답 시간 검증 (ms)
     */
    protected fun assertResponseTime(
        startTime: Long,
        maxDurationMs: Long,
    ) {
        val duration = System.currentTimeMillis() - startTime
        assertThat(duration)
            .withFailMessage("Response time ${duration}ms exceeded limit ${maxDurationMs}ms")
            .isLessThan(maxDurationMs)
    }

    // ========================================
    // Header Helpers
    // ========================================

    /**
     * Bearer Token 헤더 생성
     */
    protected fun withBearerToken(token: String): HttpHeaders = HttpHeaders().apply {
        setBearerAuth(token)
    }

    /**
     * JSON Content-Type 헤더 생성
     */
    protected fun jsonHeaders(): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
    }
}

package maple.expectation.smoke

import maple.expectation.config.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * P0 스모크 테스트 기반 클래스
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>실제 HTTP 서버 실행 (RANDOM_PORT)</li>
 *   <li>응답 시간 검증 (< 500ms 기본)</li>
 *   <li>Health Check 검증</li>
 * </ul>
 *
 * <h3>P0 Critical Paths</h3>
 * <ol>
 *   <li>Health Check - /actuator/health</li>
 *   <li>Liveness Probe - /actuator/health/liveness</li>
 *   <li>Readiness Probe - /actuator/health/readiness</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebMvc
@Tag("smoke")
abstract class SmokeTestBase {

    @Autowired
    protected lateinit var restTemplate: TestRestTemplate

    companion object {
        /** P0 경로의 최대 응답 시간 (ms) */
        const val MAX_RESPONSE_TIME_MS: Long = 500

        /**
         * PostgreSQL 동적 프로퍼티 설정
         */
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.url") { TestcontainersConfiguration.postgresContainer.jdbcUrl }
            registry.add("spring.datasource.username") { TestcontainersConfiguration.postgresContainer.username }
            registry.add("spring.datasource.password") { TestcontainersConfiguration.postgresContainer.password }
        }

        /**
         * Cache 동적 프로퍼티 설정 (테스트 환경: PostgreSQL L2 활성화)
         */
        @JvmStatic
        @DynamicPropertySource
        fun cacheProperties(registry: DynamicPropertyRegistry) {
            registry.add("cache.l2.enabled") { "true" }
            registry.add("cache.l2.impl") { "postgres" }
        }
    }

    // ========================================
    // HTTP Helpers
    // ========================================

    protected fun <T> get(path: String, responseType: Class<T>): ResponseEntity<T> = restTemplate.getForEntity(path, responseType)

    protected fun <T> post(
        path: String,
        request: Any?,
        responseType: Class<T>,
    ): ResponseEntity<T> = restTemplate.postForEntity(path, request, responseType)

    // ========================================
    // Smoke Test Assertions
    // ========================================

    /**
     * P0 응답 시간 검증
     */
    protected fun assertP0ResponseTime(startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        assertThat(duration)
            .withFailMessage("P0 path response time ${duration}ms exceeded limit ${MAX_RESPONSE_TIME_MS}ms")
            .isLessThan(MAX_RESPONSE_TIME_MS)
    }

    /**
     * Health Check 검증
     */
    protected fun assertHealthy(response: ResponseEntity<String>) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("UP")
    }

    /**
     * OK 상태 코드 검증
     */
    protected fun assertOk(response: ResponseEntity<*>) {
        assertThat(response.statusCode)
            .withFailMessage("Expected OK but got ${response.statusCode}")
            .isEqualTo(HttpStatus.OK)
    }
}

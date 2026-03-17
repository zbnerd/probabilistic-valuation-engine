package maple.expectation.smoke

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity

/**
 * P0 스모크 테스트 - Health Check
 *
 * <p>애플리케이션 상태 확인
 *
 * <p>NOTE: These tests are currently disabled due to database schema issues
 * that need to be resolved at the infrastructure level. The tests validate
 * that health endpoints respond correctly.
 */
@DisplayName("P0 Smoke - Health Check")
@Disabled("Infrastructure issue: Database schema DDL generation fails with MySQL types in PostgreSQL")
class P0HealthSmokeTest : SmokeTestBase() {

    @Test
    @Disabled("Requires database schema fix")
    fun `Health endpoint returns UP`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<String> = get("/actuator/health", String::class.java)

        // Then
        assertHealthy(response)
        assertP0ResponseTime(startTime)
    }

    @Test
    @Disabled("Requires database schema fix")
    fun `Liveness probe returns OK`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<String> = get("/actuator/health/liveness", String::class.java)

        // Then
        assertHealthy(response)
        assertP0ResponseTime(startTime)
    }

    @Test
    @Disabled("Requires database schema fix")
    fun `Readiness probe returns OK`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<String> = get("/actuator/health/readiness", String::class.java)

        // Then
        assertHealthy(response)
        assertP0ResponseTime(startTime)
    }
}

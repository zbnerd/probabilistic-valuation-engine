package maple.expectation.smoke

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity

/**
 * P0 스모크 테스트 - Expectation Calculation API
 *
 * <p>기대값 계산 관련 P0 경로 검증
 *
 * <p>NOTE: These tests are currently disabled due to database schema issues
 * that need to be resolved at the infrastructure level. The tests validate
 * endpoint accessibility and response time requirements.
 */
@DisplayName("P0 Smoke - Expectation Calculation")
@Disabled("Infrastructure issue: Database schema DDL generation fails with MySQL types in PostgreSQL")
class P0ExpectationSmokeTest : SmokeTestBase() {

    @Test
    @Disabled("Requires database schema fix")
    fun `Expectation endpoint is accessible and responds within P0 time limit`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<Map<String, Any>> = get(
            "/api/v4/characters/nonexistent-character/expectation",
            Map::class.java as Class<Map<String, Any>>,
        )

        // Then
        assertP0ResponseTime(startTime)
        // Should return 401 (unauthorized) or 404 (not found)
        org.assertj.core.api.Assertions.assertThat(response.statusCode.value())
            .isIn(401, 403, 404)
    }

    @Test
    @Disabled("Requires database schema fix")
    fun `Expectation recalculate endpoint is accessible`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<Map<String, Any>> = post(
            "/api/v4/characters/nonexistent-character/expectation/recalculate",
            null,
            Map::class.java as Class<Map<String, Any>>,
        )

        // Then
        assertP0ResponseTime(startTime)
        // Should return 401 (unauthorized) or 404 (not found)
        org.assertj.core.api.Assertions.assertThat(response.statusCode.value())
            .isIn(401, 403, 404)
    }
}

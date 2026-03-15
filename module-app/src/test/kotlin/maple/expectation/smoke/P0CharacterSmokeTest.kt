package maple.expectation.smoke

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity

/**
 * P0 스모크 테스트 - Character API
 *
 * <p>캐릭터 관련 P0 경로 검증
 *
 * <p>NOTE: These tests are currently disabled due to database schema issues
 * that need to be resolved at the infrastructure level. The tests validate
 * endpoint accessibility and response time requirements.
 */
@DisplayName("P0 Smoke - Character API")
@Disabled("Infrastructure issue: Database schema DDL generation fails with MySQL types in PostgreSQL")
class P0CharacterSmokeTest : SmokeTestBase() {

    @Test
    @Disabled("Requires database schema fix")
    fun `Character endpoint is accessible and returns expected status codes`() {
        // Given
        val startTime = System.currentTimeMillis()

        // When
        val response: ResponseEntity<Map<String, Any>> = get(
            "/api/v1/characters/nonexistent-character",
            Map::class.java as Class<Map<String, Any>>,
        )

        // Then
        assertP0ResponseTime(startTime)
        // Should return 401 (unauthorized) or 404 (not found) or 403 (forbidden)
        org.assertj.core.api.Assertions.assertThat(response.statusCode.value())
            .isIn(401, 403, 404)
    }
}

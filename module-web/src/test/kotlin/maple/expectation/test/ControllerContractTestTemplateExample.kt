package maple.expectation.test

import java.util.*
import maple.expectation.core.port.out.GameCharacterPort
import maple.expectation.web.dto.response.CharacterResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser

/**
 * ControllerContractTestTemplate 사용 예제
 *
 * <p>GameCharacterControllerV1 API 계약 테스트 예제
 *
 * <h3>테스트 대상</h3>
 * <ul>
 *   <li>GET /api/v1/characters/{userIgn} - 캐릭터 정보 조회</li>
 * </ul>
 *
 * <h3>테스트 시나리오</h3>
 * <ul>
 *   <li>성공: 존재하는 캐릭터 조회 → 200 OK</li>
 *   <li>실패: 존재하지 않는 캐릭터 → 404 NOT FOUND (Port에서 예외 발생)</li>
 *   <li>인증: 인증 없는 요청 → 401 UNAUTHORIZED</li>
 * </ul>
 *
 * <h3>참고</h3>
 * <ul>
 *   <li>실제 테스트 실행은 GameCharacterControllerV1Test.kt에서 구현</li>
 *   <li>이 클래스는 템플릿 사용 예시를 보여주는 목적</li>
 * </ul>
 */
@Disabled("Template example - requires full application context from module-app")
@DisplayName("GameCharacterControllerV1 Contract Test Example")
class ControllerContractTestTemplateExample : ControllerContractTestTemplate() {

    @Autowired
    private lateinit var gameCharacterPort: GameCharacterPort

    private val testUserIgn = "test-character-${UUID.randomUUID()}"

    @AfterEach
    fun tearDown() {
        // 테스트 데이터 정리 (DatabaseCleaner가 자동 처리)
    }

    @Test
    @DisplayName("존재하는 캐릭터 조회 - 200 OK")
    @WithMockUser(roles = ["USER"])
    fun findCharacterByUserIgn_Success_Returns200() {
        // Given
        // 테스트 캐릭터가 DB에 저장되어 있다고 가정

        // When
        val startTime = System.currentTimeMillis()
        val response = get(
            path = "/api/v1/characters/$testUserIgn",
            responseType = CharacterResponse::class.java,
        )

        // Then
        assertOk(response)
        assertResponseBodyNotNull(response)
        assertResponseTime(startTime, 500) // 500ms 이내 응답

        // 응답 데이터 검증
        assertThat(response.body?.userIgn).isEqualTo(testUserIgn)
    }

    @Test
    @DisplayName("존재하지 않는 캐릭터 조회 - 404 NOT FOUND")
    @WithMockUser(roles = ["USER"])
    fun findCharacterByUserIgn_NotFound_Returns404() {
        // Given
        val nonExistentIgn = "non-existent-${UUID.randomUUID()}"

        // When
        val response = get(
            path = "/api/v1/characters/$nonExistentIgn",
            responseType = CharacterResponse::class.java,
        )

        // Then
        assertNotFound(response)
    }

    @Test
    @DisplayName("인증 없는 요청 - 401 UNAUTHORIZED")
    fun findCharacterByUserIgn_Unauthorized_Returns401() {
        // Given - @WithMockUser 없음

        // When
        val response = get(
            path = "/api/v1/characters/$testUserIgn",
            responseType = CharacterResponse::class.java,
        )

        // Then
        assertUnauthorized(response)
    }

    @Test
    @DisplayName("Bearer Token 인증 - 200 OK")
    fun findCharacterByUserIgn_WithBearerToken_Returns200() {
        // Given
        val token = "valid-jwt-token" // 실제로는 TestFixture에서 발급

        // When
        val response = get(
            path = "/api/v1/characters/$testUserIgn",
            responseType = CharacterResponse::class.java,
            headers = withBearerToken(token),
        )

        // Then
        assertOk(response)
    }

    @Test
    @DisplayName("잘못된 요청 - 빈 userIgn으로 조회")
    @WithMockUser(roles = ["USER"])
    fun findCharacterByUserIgn_EmptyUserIgn_Returns400() {
        // Given - 빈 문자열

        // When
        val response = get(
            path = "/api/v1/characters/", // 또는 빈 문자열
            responseType = CharacterResponse::class.java,
        )

        // Then
        assertBadRequest(response)
    }
}

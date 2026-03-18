package maple.expectation.testfixtures

import maple.expectation.response.ApiResponse
import maple.expectation.web.dto.LoginRequest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * API Test Utilities for integration tests
 */

/**
 * Authenticate user and return JWT token
 *
 * @param restTemplate TestRestTemplate for making HTTP requests
 * @param apiKey User's API key
 * @param userIgn User's in-game name
 * @return JWT access token
 */
fun authenticateUser(
    restTemplate: TestRestTemplate,
    apiKey: String = "test-api-key",
    userIgn: String = "test-user",
): String {
    val headers = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
    }

    val loginRequest = LoginRequest(apiKey, userIgn)
    val entity = HttpEntity(loginRequest, headers)

    val response = restTemplate.postForEntity(
        "/auth/login",
        entity,
        ApiResponse::class.java,
    )

    assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    assertThat(response.body?.success).isTrue()
    assertThat(response.body?.data).isNotNull()

    @Suppress("UNCHECKED_CAST")
    val data = response.body?.data as? Map<String, Any?>
    return data?.get("accessToken") as? String
        ?: throw IllegalStateException("Access token not found in login response")
}

/**
 * Extension function to add Authorization header
 */
fun HttpHeaders.withAuthHeader(token: String): HttpHeaders {
    this.setBearerAuth(token)
    return this
}

/**
 * Assert successful response (2xx)
 */
fun <T> assertSuccessResponse(response: ResponseEntity<T>, expectedStatus: HttpStatus = HttpStatus.OK) {
    assertThat(response.statusCode).isEqualTo(expectedStatus)
}

/**
 * Assert error response (4xx/5xx)
 */
fun <T> assertErrorResponse(response: ResponseEntity<T>, expectedStatus: HttpStatus) {
    assertThat(response.statusCode).isEqualTo(expectedStatus)
}

/**
 * Assert response body contains expected value
 */
fun <T> assertResponseBodyContains(response: ResponseEntity<T>, predicate: (T?) -> Boolean) {
    assertThat(predicate(response.body)).isTrue()
}

package maple.expectation.web.controller

import jakarta.validation.Valid
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.AuthCommand
import maple.expectation.core.port.inbound.AuthPort
import maple.expectation.core.port.inbound.AuthResult
import maple.expectation.core.port.inbound.TokenResult
import maple.expectation.core.domain.model.security.AuthenticatedUser
import maple.expectation.response.ApiResponse
import maple.expectation.web.dto.LoginRequest
import maple.expectation.web.dto.LoginResponse
import maple.expectation.web.dto.RefreshRequest
import maple.expectation.web.dto.TokenResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 인증 API 컨트롤러
 *
 * API 목록:
 * - POST /auth/login - 로그인 (JWT + Refresh Token 발급)
 * - POST /auth/refresh - 토큰 갱신 (Issue #279)
 * - DELETE /auth/logout - 로그아웃 (세션 + Refresh Token 삭제)
 * - GET /auth/me - 현재 사용자 정보 조회
 *
 * **Issue #151: Bean Validation 적용**
 * - @Validated: 클래스 레벨 검증 활성화
 * - @Valid: @RequestBody DTO 검증
 */
@Validated
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authPort: AuthPort,
) {

    /**
     * 로그인 API
     *
     * @param request 로그인 요청 (apiKey, userIgn)
     * @return 로그인 응답 (accessToken, expiresIn, role, refreshToken, refreshExpiresIn)
     */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): CompletableFuture<ResponseEntity<ApiResponse<LoginResponse>>> = CompletableFuture.supplyAsync {
        val command = AuthCommand.of(request.apiKey, request.userIgn)
        val result: AuthResult = authPort.login(command)
        val response = LoginResponse.of(
            result.accessToken,
            result.expiresIn,
            result.role,
            result.fingerprint,
            result.refreshToken,
            result.refreshExpiresIn,
        )
        ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 토큰 갱신 API (Issue #279)
     *
     * **Token Rotation 패턴:**
     * - 기존 Refresh Token 무효화
     * - 새 Access Token + Refresh Token 발급
     *
     * @param request 갱신 요청 (refreshToken)
     * @return 새 토큰 응답 (accessToken, accessExpiresIn, refreshToken, refreshExpiresIn)
     */
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<ApiResponse<TokenResponse>> {
        val result: TokenResult = authPort.refresh(request.refreshToken)
        val response = TokenResponse.of(
            result.accessToken,
            result.expiresIn,
            result.refreshToken,
            result.refreshExpiresIn,
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 로그아웃 API
     *
     * @param user 인증된 사용자 정보
     */
    @DeleteMapping("/logout")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun logout(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<ApiResponse<Void?>> {
        authPort.logout(user.sessionId)
        return ResponseEntity.ok(ApiResponse.success(null))
    }

    /**
     * 현재 사용자 정보 조회 API
     *
     * @param user 인증된 사용자 정보
     * @return 사용자 정보 (apiKey 제외)
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    fun me(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<ApiResponse<UserInfoResponse>> {
        val response = UserInfoResponse(
            user.sessionId,
            user.fingerprint,
            user.role,
            user.myOcids.size,
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    /** 현재 사용자 정보 응답 DTO (apiKey 제외) */
    data class UserInfoResponse(
        val sessionId: String,
        val fingerprint: String,
        val role: String,
        val characterCount: Int,
    )

    companion object {
        private val log = LoggerFactory.getLogger(AuthController::class.java)
    }
}

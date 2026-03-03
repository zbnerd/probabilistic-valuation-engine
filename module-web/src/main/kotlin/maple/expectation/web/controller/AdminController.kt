package maple.expectation.web.controller

import jakarta.validation.Valid
import maple.expectation.web.dto.admin.AddAdminRequest
import maple.expectation.core.port.inbound.AdminPort
import maple.expectation.infrastructure.security.AuthenticatedUser
import maple.expectation.response.ApiResponse
import maple.expectation.util.StringMaskingUtils
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

/**
 * Admin 관리 API
 *
 * 권한: ADMIN만 접근 가능 (SecurityConfig에서 설정)
 *
 * **Issue #151: Bean Validation 적용**
 * - @Validated: 클래스 레벨 검증 활성화 (@PathVariable 검증)
 * - @Valid: @RequestBody DTO 검증
 * - AddAdminRequest: 별도 파일로 분리 (SRP 준수)
 *
 * 엔드포인트:
 * - GET /api/admin/admins - Admin 목록 조회
 * - POST /api/admin/admins - 새 Admin 추가
 * - DELETE /api/admin/admins/{fingerprint} - Admin 제거
 */
@Validated
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminPort: AdminPort
) {

    /** 전체 Admin 목록 조회 */
    @GetMapping("/admins")
    @PreAuthorize("hasRole('ADMIN')")
    fun getAdmins(): CompletableFuture<ResponseEntity<ApiResponse<Set<String>>>> {
        return CompletableFuture.supplyAsync {
            val admins = adminPort.getAllAdmins()
            ResponseEntity.ok(ApiResponse.success(admins))
        }
    }

    /**
     * 새 Admin 추가
     *
     * **Issue #151: @Valid 적용**
     * AddAdminRequest에 @NotBlank, @Size, @Pattern 검증 적용
     *
     * @param request fingerprint가 담긴 요청 (검증됨)
     */
    @PostMapping("/admins")
    @PreAuthorize("hasRole('ADMIN')")
    fun addAdmin(
        @Valid @RequestBody request: AddAdminRequest,
        @AuthenticationPrincipal currentUser: AuthenticatedUser
    ): CompletableFuture<ResponseEntity<ApiResponse<String>>> {
        return CompletableFuture.supplyAsync {
            adminPort.addAdmin(request.fingerprint)
            ResponseEntity.ok(
                ApiResponse.success(
                    "Admin added successfully: ${request.maskedFingerprint()}"
                )
            )
        }
    }

    /**
     * Admin 제거
     *
     * @param fingerprint 제거할 Admin의 fingerprint
     */
    @DeleteMapping("/admins/{fingerprint}")
    @PreAuthorize("hasRole('ADMIN')")
    fun removeAdmin(
        @PathVariable fingerprint: String,
        @AuthenticationPrincipal currentUser: AuthenticatedUser
    ): CompletableFuture<ResponseEntity<ApiResponse<String>>> {
        return CompletableFuture.supplyAsync {
            // 자기 자신은 제거 불가
            if (fingerprint == currentUser.fingerprint) {
                return@supplyAsync ResponseEntity.badRequest()
                    .body(
                        ApiResponse.error("SELF_REMOVAL_NOT_ALLOWED", "자기 자신의 Admin 권한은 제거할 수 없습니다.")
                    )
            }

            val removed = adminPort.removeAdmin(fingerprint)

            if (!removed) {
                return@supplyAsync ResponseEntity.badRequest()
                    .body(ApiResponse.error("BOOTSTRAP_ADMIN", "Bootstrap Admin은 제거할 수 없습니다."))
            }

            ResponseEntity.ok(
                ApiResponse.success(
                    "Admin removed successfully: ${StringMaskingUtils.maskFingerprintWithSuffix(fingerprint)}"
                )
            )
        }
    }
}

package maple.restcontroller.controller.v6

import maple.auth.login.LoginRejectedException
import maple.auth.login.LoginService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/v6/auth")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class AuthController(
    private val loginService: LoginService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): CompletableFuture<ResponseEntity<Map<String, Any>>> =
        loginService.login(request.apiKey, request.userIgn)
            .handle { result, ex ->
                if (ex != null) {
                    val cause = ex.cause ?: ex
                    when (cause) {
                        is LoginRejectedException -> ResponseEntity
                            .status(cause.statusCode)
                            .body(mapOf("error" to (cause.message ?: "Authentication failed"), "status" to cause.statusCode))
                        else -> ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(mapOf("error" to "Internal error", "status" to 500))
                    }
                } else {
                    ResponseEntity.ok(mapOf(
                        "token" to result.token,
                        "sessionId" to result.sessionId,
                        "fingerprint" to result.fingerprint,
                        "userIgn" to result.userIgn,
                        "characterCount" to result.characterCount,
                        "cached" to result.cached,
                    ))
                }
            }
}

data class LoginRequest(val apiKey: String, val userIgn: String)

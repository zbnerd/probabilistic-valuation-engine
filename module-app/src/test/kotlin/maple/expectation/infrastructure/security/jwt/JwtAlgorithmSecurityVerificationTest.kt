package maple.expectation.infrastructure.security.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.support.TestLogicExecutors
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

/**
 * Verification test for JWT Algorithm Confusion Attack Prevention (Unit 1)
 */
@DisplayName("JWT Algorithm Security Verification (Unit 1)")
class JwtAlgorithmSecurityVerificationTest {

    private lateinit var tokenProvider: JwtTokenProvider
    private val secretKey = Keys.hmacShaKeyFor("test-secret-key-for-jwt-testing-32chars".toByteArray(StandardCharsets.UTF_8))

    @BeforeEach
    fun setUp() {
        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.activeProfiles).thenReturn(arrayOf("test"))
        val executor = TestLogicExecutors.passThrough()
        tokenProvider = JwtTokenProvider(
            "test-secret-key-for-jwt-testing-32chars",
            3600L,
            environment,
            executor
        )
        tokenProvider.init()
    }

    @Test
    @DisplayName("Should reject token with 'none' algorithm (P0: Algorithm Confusion Attack)")
    fun shouldRejectNoneAlgorithm() {
        val header = base64UrlEncode("{\"alg\":\"none\",\"typ\":\"JWT\"}")
        val payload = base64UrlEncode("{\"sub\":\"attacker\",\"exp\":9999999999}")
        val noneToken = "$header.$payload."

        val result = tokenProvider.parseToken(noneToken)
        assert(result.isEmpty) { "Token with 'none' algorithm should be rejected" }
        println("✓ P0: 'none' algorithm correctly rejected")
    }

    @Test
    @DisplayName("Should accept valid HS256 token")
    fun shouldAcceptValidHS256Token() {
        val now = Date()
        val expiration = Date(now.time + 3600000) // 1 hour from now
        val validToken = Jwts.builder()
            .subject("test-session")
            .claim("fgp", "test-fingerprint")
            .claim("role", "USER")
            .issuedAt(now)
            .expiration(expiration)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()

        val result = tokenProvider.parseToken(validToken)
        assert(result.isPresent) { "Valid HS256 token should be accepted" }
        println("✓ Valid HS256 token correctly accepted")
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(StandardCharsets.UTF_8))
    }
}

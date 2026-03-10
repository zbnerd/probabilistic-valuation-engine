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
 *
 * This test verifies that the JwtTokenProvider correctly rejects tokens
 * with malicious algorithm headers.
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
        // Create a token with "none" algorithm (no signature)
        val header = base64UrlEncode("{\"alg\":\"none\",\"typ\":\"JWT\"}")
        val payload = base64UrlEncode("{\"sub\":\"attacker\",\"exp\":9999999999}")
        val noneToken = "$header.$payload."

        val result = tokenProvider.parseToken(noneToken)

        // Should return empty (rejected)
        assert(result.isEmpty) { "Token with 'none' algorithm should be rejected" }
        println("✓ P0: 'none' algorithm correctly rejected")
    }

    @Test
    @DisplayName("Should reject token with 'NONE' algorithm (case-insensitive)")
    fun shouldRejectUPPERNoneAlgorithm() {
        val header = base64UrlEncode("{\"alg\":\"NONE\",\"typ\":\"JWT\"}")
        val payload = base64UrlEncode("{\"sub\":\"attacker\",\"exp\":9999999999}")
        val noneToken = "$header.$payload."

        val result = tokenProvider.parseToken(noneToken)

        assert(result.isEmpty) { "Token with 'NONE' algorithm should be rejected" }
        println("✓ P0: 'NONE' algorithm correctly rejected")
    }

    @Test
    @DisplayName("Should accept valid HS256 token")
    fun shouldAcceptValidHS256Token() {
        // Create a valid HS256 token using JJWT
        val validToken = Jwts.builder()
            .subject("test-session")
            .claim("fgp", "test-fingerprint")
            .claim("role", "USER")
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()

        val result = tokenProvider.parseToken(validToken)

        assert(result.isPresent) { "Valid HS256 token should be accepted" }
        println("✓ Valid HS256 token correctly accepted")
    }

    @Test
    @DisplayName("Should reject HS512 token (not in whitelist)")
    fun shouldRejectHS512Token() {
        // Create a valid HS512 token (stronger, but not in our whitelist)
        val hs512Token = Jwts.builder()
            .subject("test-session")
            .claim("fgp", "test-fingerprint")
            .claim("role", "USER")
            .signWith(secretKey, Jwts.SIG.HS512)
            .compact()

        val result = tokenProvider.parseToken(hs512Token)

        // Should be rejected because HS512 is not in ALLOWED_ALGORITHMS
        assert(result.isEmpty) { "HS512 token should be rejected (not in whitelist)" }
        println("✓ HS512 token correctly rejected (not in whitelist)")
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(StandardCharsets.UTF_8))
    }
}

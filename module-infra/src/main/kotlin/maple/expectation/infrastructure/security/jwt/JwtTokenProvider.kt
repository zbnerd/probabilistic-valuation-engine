package maple.expectation.infrastructure.security.jwt

import io.jsonwebtoken.Jws
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Optional
import javax.crypto.SecretKey
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * JWT 토큰 생성 및 검증을 담당하는 Provider
 *
 * <p>JJWT 0.12.x Best Practice 적용:
 *
 * <ul>
 *   <li>HS256 알고리즘 사용
 *   <li>parseSignedClaims() 사용 (deprecated된 parseClaimsJws 대체)
 *   <li>프로덕션 환경에서 기본 secret 사용 시 시작 거부
 * </ul>
 */
@Component
class JwtTokenProvider(
    @Value("\${auth.jwt.secret}") private val secret: String,
    @Value("\${auth.jwt.expiration}") private val expirationSeconds: Long,
    private val environment: Environment,
    private val executor: LogicExecutor,
) {
    private lateinit var secretKey: SecretKey

    companion object {
        private const val ISSUER = "maple-expectation"
        private const val CLAIM_FINGERPRINT = "fgp"
        private const val CLAIM_ROLE = "role"
        private const val CLAIM_USER_IGN = "userIgn"
        private const val DEFAULT_SECRET_PREFIX = "dev-secret"
        private const val PLACEHOLDER_PATTERN = "\${"
        private const val MIN_SECRET_LENGTH = 32

        /**
         * JWT Algorithm Whitelist (P0: Algorithm Confusion Attack Prevention)
         *
         * <p>Only HS256 (HMAC-SHA256) is allowed for token signing and verification.
         * This prevents algorithm confusion attacks where attackers may try to:
         *
         * <ul>
         *   <li>Switch to "none" algorithm (bypass signature verification)
         *   <li>Switch to weaker algorithms (HS256, HS512)
         *   <li>Switch to asymmetric algorithms (RS256, ES256) with public key
         * </ul>
         *
         * @see <a href="https://datatracker.ietf.org/doc/html/rfc8725">RFC 8725: JWT Best Practices</a>
         */
        private val ALLOWED_ALGORITHMS = setOf("HS256")

        /**
         * Forbidden algorithm variants (case-insensitive matching required)
         * These are known attack vectors for algorithm confusion.
         */
        private val FORBIDDEN_ALGORITHMS = setOf("none", "nOnE", "NONE", "None")

        /**
         * Expected algorithm constant for validation messages
         */
        private const val EXPECTED_ALGORITHM = "HS256"
    }

    @PostConstruct
    fun init() {
        validateSecretKeyForProduction()
        this.secretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        println("JWT TokenProvider initialized with expiration: ${expirationSeconds}s")
    }

    /**
     * JWT Secret Key 유효성 검증 (Issue #19)
     *
     * <p>모든 환경에서 fail-fast로 보안을 강화합니다:
     *
     * <ul>
     *   <li>환경변수 미설정 감지 (placeholder 패턴)
     *   <li>빈 값 감지
     *   <li>프로덕션 환경에서 기본 개발용 secret 사용 거부
     *   <li>HS256 알고리즘 최소 길이(32자) 검증
     * </ul>
     */
    private fun validateSecretKeyForProduction() {
        // 1. 빈 값 또는 null 감지 (모든 환경에서 fail-fast) - 먼저 체크해야 NPE 방지
        require(secret.isNotBlank()) {
            "JWT_SECRET must not be null or blank. Please set the JWT_SECRET environment variable."
        }

        // 2. 환경변수 placeholder 감지 (모든 환경에서 fail-fast)
        require(!secret.contains(PLACEHOLDER_PATTERN)) {
            "JWT_SECRET environment variable is not set. " +
                "The secret contains an unresolved placeholder: ${maskSecretForLogging(secret)}"
        }

        // 3. 프로덕션 환경에서 기본 개발용 secret 사용 거부
        val isProduction = environment.activeProfiles.contains("prod")
        val isDefaultSecret = secret.startsWith(DEFAULT_SECRET_PREFIX)

        require(!(isProduction && isDefaultSecret)) {
            "JWT_SECRET must be set in production environment. " +
                "Default development secret is not allowed in production."
        }

        // 4. HS256 알고리즘 최소 길이 검증 (모든 환경)
        require(secret.length >= MIN_SECRET_LENGTH) {
            "JWT secret must be at least $MIN_SECRET_LENGTH characters for HS256 algorithm (current: ${secret.length})"
        }
    }

    /** 비밀키 로그 출력 시 노출 방지를 위한 마스킹 */
    private fun maskSecretForLogging(value: String): String = if (value.length < 8) {
        "***"
    } else {
        value.substring(0, 4) + "..." + value.substring(value.length - 4)
    }

    /**
     * JWT 토큰을 생성합니다.
     *
     * @param payload 토큰에 담을 페이로드
     * @return 생성된 JWT 토큰 문자열
     */
    fun generateToken(payload: JwtPayload): String = Jwts.builder()
        .issuer(ISSUER)
        .subject(payload.sessionId)
        .claim(CLAIM_FINGERPRINT, payload.fingerprint)
        .claim(CLAIM_ROLE, payload.role)
        .claim(CLAIM_USER_IGN, payload.userIgn)
        .issuedAt(Date.from(payload.issuedAt))
        .expiration(Date.from(payload.expiration))
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact()

    /**
     * 세션 ID, fingerprint, role로 토큰을 생성합니다.
     *
     * @param sessionId 세션 ID
     * @param fingerprint fingerprint
     * @param role 권한
     * @return 생성된 JWT 토큰 문자열
     */
    fun generateToken(sessionId: String, fingerprint: String, role: String): String {
        val payload = JwtPayload.of(sessionId, fingerprint, role, expirationSeconds)
        return generateToken(payload)
    }

    /**
     * 세션 ID, fingerprint, role, userIgn로 토큰을 생성합니다.
     *
     * @param sessionId 세션 ID
     * @param fingerprint fingerprint
     * @param role 권한
     * @param userIgn 캐릭터 닉네임
     * @return 생성된 JWT 토큰 문자열
     */
    fun generateToken(sessionId: String, fingerprint: String, role: String, userIgn: String): String {
        val payload = JwtPayload.of(sessionId, fingerprint, role, expirationSeconds, userIgn)
        return generateToken(payload)
    }

    /**
     * JWT 토큰을 파싱하여 페이로드를 추출합니다. (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
     *
     * @param token JWT 토큰 문자열
     * @return 파싱된 JwtPayload (Optional)
     */
    fun parseToken(token: String?): Optional<JwtPayload> = executor.executeOrDefault(
        { parseTokenInternal(token) },
        Optional.empty(),
        TaskContext.of("JWT", "ParseToken", maskToken(token)),
    )

    private fun parseTokenInternal(token: String?): Optional<JwtPayload> {
        // P0: Algorithm confusion attack prevention - pre-parse validation
        // Check token format and extract header before parsing to reject "none" algorithm early
        require(token != null && token.isNotBlank()) {
            "JWT token must not be null or blank"
        }

        // Extract and validate header algorithm BEFORE signature verification
        // This prevents algorithm confusion attacks where attacker sets alg="none"
        val headerAlgorithm = extractAlgorithmFromHeader(token)

        // P0: Explicit "none" algorithm rejection (case-insensitive)
        require(headerAlgorithm.lowercase() !in FORBIDDEN_ALGORITHMS.map { it.lowercase() }) {
            "JWT algorithm 'none' is forbidden. This is a known algorithm confusion attack vector. " +
                "Received: '$headerAlgorithm'"
        }

        // P0: Algorithm whitelist enforcement
        require(headerAlgorithm in ALLOWED_ALGORITHMS) {
            "JWT algorithm not in whitelist. Allowed: $ALLOWED_ALGORITHMS, Received: '$headerAlgorithm'. " +
                "Possible algorithm confusion attack."
        }

        // JJWT 0.12.x: verifyWith() ensures HMAC signature verification with SecretKey
        // The explicit algorithm check above provides defense-in-depth
        val jws: Jws<io.jsonwebtoken.Claims> = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)

        // P0: Defense-in-depth - verify parsed algorithm matches expected (should never fail if above checks pass)
        val parsedAlgorithm = jws.header.algorithm
        require(parsedAlgorithm == EXPECTED_ALGORITHM) {
            "JWT algorithm mismatch after parsing: expected $EXPECTED_ALGORITHM, got $parsedAlgorithm. " +
                "Possible algorithm confusion attack."
        }

        val claims: io.jsonwebtoken.Claims = jws.payload

        val payload = JwtPayload(
            claims.subject,
            claims[CLAIM_FINGERPRINT, String::class.java],
            claims[CLAIM_ROLE, String::class.java],
            claims[CLAIM_USER_IGN, String::class.java] ?: "",
            claims.issuedAt.toInstant(),
            claims.expiration.toInstant(),
        )

        return Optional.of(payload)
    }

    /**
     * Extract algorithm from JWT header without full parsing (P0: Early algorithm validation)
     *
     * <p>This method extracts the 'alg' field from the JWT header before signature verification
     * to enable early rejection of forbidden algorithms like "none".
     *
     * @param token JWT token string
     * @return algorithm string from header
     * @throws IllegalArgumentException if token format is invalid
     */
    private fun extractAlgorithmFromHeader(token: String): String {
        val parts = token.split(".")
        require(parts.size == 3) {
            "Invalid JWT format: expected 3 parts (header.payload.signature), got ${parts.size}"
        }

        // Decode header (Base64URL) - use JJWT's built-in decoder
        val headerBytes = io.jsonwebtoken.io.Decoders.BASE64URL.decode(parts[0])
        val headerJson = String(headerBytes, Charsets.UTF_8)

        // Extract "alg" field using simple JSON parsing
        // Use regex to avoid heavy JSON library dependency for this simple extraction
        val algPattern = """"alg"\s*:\s*"([^"]+)"""".toRegex()
        val match = algPattern.find(headerJson)

        return match?.groupValues?.get(1) ?: throw IllegalArgumentException(
            "JWT header missing 'alg' field. Header: $headerJson",
        )
    }

    private fun maskToken(token: String?): String = if (token == null || token.length < 10) {
        "***"
    } else {
        token.substring(0, 6) + "..."
    }

    /**
     * JWT 토큰의 유효성을 검증합니다.
     *
     * @param token JWT 토큰 문자열
     * @return 유효 여부
     */
    fun validateToken(token: String?): Boolean = parseToken(token).isPresent

    /**
     * 토큰의 기본 만료 시간(초)을 반환합니다.
     *
     * @return 만료 시간 (초)
     */
    fun getExpirationSeconds(): Long = expirationSeconds
}

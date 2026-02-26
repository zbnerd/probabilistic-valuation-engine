package maple.expectation.infrastructure.security.jwt

import io.jsonwebtoken.Jws
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Optional
import javax.crypto.SecretKey

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
    private val executor: LogicExecutor
) {
    private lateinit var secretKey: SecretKey

    companion object {
        private const val ISSUER = "maple-expectation"
        private const val CLAIM_FINGERPRINT = "fgp"
        private const val CLAIM_ROLE = "role"
        private const val DEFAULT_SECRET_PREFIX = "dev-secret"
        private const val PLACEHOLDER_PATTERN = "\${"
        private const val MIN_SECRET_LENGTH = 32
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
    private fun maskSecretForLogging(value: String): String {
        return if (value.length < 8) {
            "***"
        } else {
            value.substring(0, 4) + "..." + value.substring(value.length - 4)
        }
    }

    /**
     * JWT 토큰을 생성합니다.
     *
     * @param payload 토큰에 담을 페이로드
     * @return 생성된 JWT 토큰 문자열
     */
    fun generateToken(payload: JwtPayload): String {
        return Jwts.builder()
            .issuer(ISSUER)
            .subject(payload.sessionId)
            .claim(CLAIM_FINGERPRINT, payload.fingerprint)
            .claim(CLAIM_ROLE, payload.role)
            .issuedAt(Date.from(payload.issuedAt))
            .expiration(Date.from(payload.expiration))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

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
     * JWT 토큰을 파싱하여 페이로드를 추출합니다. (CLAUDE.md Section 12 준수: LogicExecutor 패턴)
     *
     * @param token JWT 토큰 문자열
     * @return 파싱된 JwtPayload (Optional)
     */
    fun parseToken(token: String?): Optional<JwtPayload> {
        return executor.executeOrDefault(
            { parseTokenInternal(token) },
            Optional.empty(),
            TaskContext.of("JWT", "ParseToken", maskToken(token))
        )
    }

    private fun parseTokenInternal(token: String?): Optional<JwtPayload> {
        val jws: Jws<io.jsonwebtoken.Claims> = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)

        val claims: io.jsonwebtoken.Claims = jws.payload

        val payload = JwtPayload(
            claims.subject,
            claims[CLAIM_FINGERPRINT, String::class.java],
            claims[CLAIM_ROLE, String::class.java],
            claims.issuedAt.toInstant(),
            claims.expiration.toInstant()
        )

        return Optional.of(payload)
    }

    private fun maskToken(token: String?): String {
        return if (token == null || token.length < 10) {
            "***"
        } else {
            token.substring(0, 6) + "..."
        }
    }

    /**
     * JWT 토큰의 유효성을 검증합니다.
     *
     * @param token JWT 토큰 문자열
     * @return 유효 여부
     */
    fun validateToken(token: String?): Boolean {
        return parseToken(token).isPresent
    }

    /**
     * 토큰의 기본 만료 시간(초)을 반환합니다.
     *
     * @return 만료 시간 (초)
     */
    fun getExpirationSeconds(): Long {
        return expirationSeconds
    }
}

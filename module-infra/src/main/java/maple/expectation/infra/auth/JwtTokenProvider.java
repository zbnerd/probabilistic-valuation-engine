package maple.expectation.infra.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.TokenPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 토큰 생성 및 검증 구현체 (TokenPort)
 *
 * <p>의존성: io.jsonwebtoken:jjwt-api 0.12.6
 *
 * <p>구현 포트: {@link maple.expectation.core.port.out.TokenPort}
 */
@Slf4j
@Component
public class JwtTokenProvider implements TokenPort {

  private final SecretKey key;

  public JwtTokenProvider(@Value("${jwt.secret}") String secret) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String generateToken(long userId) {
    return Jwts.builder().subject(String.valueOf(userId)).signWith(key).compact();
  }

  @Override
  public Long validateToken(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      return Long.parseLong(claims.getSubject());
    } catch (Exception e) {
      log.debug("Token validation failed: {}", e.getMessage());
      return null;
    }
  }
}

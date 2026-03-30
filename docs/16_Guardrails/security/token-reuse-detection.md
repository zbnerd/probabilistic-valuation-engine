---
id: GR-SEC-012
category: security
severity: warning
keywords: [JWT, TokenReuse, RedisSession, Security, CompromiseDetection]
---

# JWT Token Reuse Detection

## DON'T (안티패턴)

### 1. 토큰 재사용 감지 없음
```java
// Bad: 같은 토큰이 여러 번 사용되어도 차단하지 않음
public boolean validateToken(String token) {
    try {
        Jws<Claims> claims = jwtTokenProvider.parseClaims(token);
        return claims != null;
    } catch (Exception e) {
        return false;
    }
}
```

**영향:**
- 토큰 탈취 시 무제한 사용 가능
- 재생 공격(Replay Attack) 방지 불가
- 보안 사고 시 타임라인 추적 어려움

### 2. 토큰별 UUID 관리 안 함
```java
// Bad: 토큰 자체가 ID로 사용됨
public String generateToken(String sessionId) {
    return Jwts.builder()
        .setSubject(sessionId)
        .compact();
}
```

### 3. 로그아웃 시 토큰 미삭제
```java
// Bad: 로그아웃해도 토큰 유효
public void logout(String token) {
    // Redis 세션만 삭제, 토큰은 그대로 유효
    redisTemplate.delete("session:" + sessionId);
}
```

## DO (베스트 프랙티스)

### 1. 토큰별 UUID 관리
```java
// Good: 각 토큰에 고유 UUID 부여
public String generateToken(String sessionId, String fingerprint) {
    // 토큰별 고유 UUID 생성
    String tokenId = UUID.randomUUID().toString();

    // Redis에 토큰 ID 저장
    String tokenKey = "session:" + sessionId + ":token";
    redisTemplate.opsForValue().set(tokenKey, tokenId, expiration, TimeUnit.SECONDS);

    // JWT에 tokenId 포함
    return Jwts.builder()
        .subject(sessionId)
        .claim("tid", tokenId)  // Token ID
        .claim("fgp", fingerprint)
        .signWith(key)
        .compact();
}
```

### 2. 토큰 재사용 감지
```java
// Good: 토큰 사용 시마다 검증
public boolean validateToken(String token) {
    try {
        // 1. JWT 서명 검증
        Jws<Claims> claims = jwtTokenProvider.parseSignedClaims(token);
        String sessionId = claims.getPayload().getSubject();
        String tokenId = claims.getPayload().get("tid", String.class);

        // 2. Redis 세션 확인
        Boolean sessionExists = redisTemplate.hasKey("session:" + sessionId);
        if (!sessionExists) {
            log.warn("[Token-Security] Session expired: {}", sessionId);
            return false;
        }

        // 3. 토큰 ID 검증 (재사용 감지)
        String tokenKey = "session:" + sessionId + ":token";
        String storedTokenId = redisTemplate.opsForValue().get(tokenKey);

        if (!tokenId.equals(storedTokenId)) {
            log.warn("[Token-Security] Token reuse detected: sessionId={}, tokenId={}, expected={}",
                sessionId, tokenId, storedTokenId);

            // 보안 이벤트 기록
            meterRegistry.counter("token.security.reuse_detected",
                "session", sessionId).increment();

            return false;
        }

        return true;

    } catch (Exception e) {
        log.error("[Token-Security] Token validation failed", e);
        return false;
    }
}
```

### 3. 토큰 갱신 시 ID 교체
```java
// Good: Refresh 시 새 토큰 ID 발급
public String refreshToken(String oldToken) {
    // 1. 기존 토큰 검증
    if (!validateToken(oldToken)) {
        throw new InvalidTokenException("Invalid token");
    }

    String sessionId = getSessionId(oldToken);

    // 2. 새 토큰 ID 생성
    String newTokenId = UUID.randomUUID().toString();
    String tokenKey = "session:" + sessionId + ":token";
    redisTemplate.opsForValue().set(tokenKey, newTokenId, expiration, TimeUnit.SECONDS);

    // 3. 새 JWT 발급
    return Jwts.builder()
        .subject(sessionId)
        .claim("tid", newTokenId)
        .claim("fgp", getFingerprint(oldToken))
        .signWith(key)
        .compact();
}
```

### 4. 로그아웃 시 토큰 무효화
```java
// Good: 로그아웃 시 세션과 토큰 ID 모두 삭제
public void logout(String token) {
    String sessionId = getSessionId(token);

    // 세션 삭제
    redisTemplate.delete("session:" + sessionId);

    // 토큰 ID 삭제
    redisTemplate.delete("session:" + sessionId + ":token");

    log.info("[Token-Security] Token invalidated: sessionId={}", sessionId);
}
```

### 5. 토큰 재사용 알림
```java
// Good: 재사용 감지 시 알림
@Service
public class TokenSecurityService {

    private final MeterRegistry meterRegistry;
    private final AlertService alertService;

    public boolean validateToken(String token) {
        // ... 검증 로직 ...

        if (!tokenId.equals(storedTokenId)) {
            // 메트릭 기록
            meterRegistry.counter("token.security.reuse_detected",
                "session", sessionId).increment();

            // 보안 알림 (의심스러운 활동)
            if (isSuspiciousActivity(sessionId)) {
                alertService.sendSecurityAlert(
                    "Token Reuse Detected",
                    String.format("Possible token theft: sessionId=%s", sessionId)
                );
            }

            // 해당 세션 강제 만료
            forceExpireSession(sessionId);

            return false;
        }

        return true;
    }

    private boolean isSuspiciousActivity(String sessionId) {
        // 짧은 시간 내 다른 IP에서 접근 등
        String lastIpKey = "session:" + sessionId + ":last-ip";
        String currentIp = getCurrentIp();

        String lastIp = redisTemplate.opsForValue().get(lastIpKey);
        if (lastIp != null && !lastIp.equals(currentIp)) {
            return true;
        }

        redisTemplate.opsForValue().set(lastIpKey, currentIp, 5, TimeUnit.MINUTES);
        return false;
    }

    private void forceExpireSession(String sessionId) {
        redisTemplate.delete("session:" + sessionId);
        redisTemplate.delete("session:" + sessionId + ":token");
        log.warn("[Token-Security] Session force expired: {}", sessionId);
    }
}
```

### 6. Redis 데이터 구조
```
session:abc123 -> {
    "apiKey": "live_xxx",
    "fingerprint": "abc123...",
    "userId": "user1",
    "createdAt": "2026-02-25T10:00:00Z"
}

session:abc123:token -> "uuid-1" (토큰 ID)
session:abc123:last-ip -> "203.0.113.1" (마지막 접근 IP)
session:abc123:last-seen -> "2026-02-25T10:30:00Z" (마지막 활동)
```

## Monitoring & Alerts

```prometheus
# 토큰 재사용 감지
ALERT TokenReuseDetected
  IF rate(token_security_reuse_detected_total[5m]) > 0.01
  SEVERITY critical

  ANNOTATIONS {
    summary = "Token reuse detected",
    description = "Possible token theft or replay attack"
  }

# 세션 강제 만료
ALERT SessionForceExpired
  IF rate(session_force_expired_total[5m]) > 0.01
  SEVERITY warning

  ANNOTATIONS {
    summary = "Session force expired",
    description = "Suspicious activity detected"
  }

# 다중 IP 접근
ALERT MultipleIPAccess
  IF count(session_last_ip) by (session_id) > 1
  SEVERITY warning

  ANNOTATIONS {
    summary = "Same session accessed from multiple IPs",
    description = "Possible token sharing or theft"
  }
```

## Verification Commands

```bash
# 1. 토큰 UUID 확인
redis-cli HGETALL session:abc123

# 2. 토큰 ID 확인
redis-cli GET session:abc123:token

# 3. 재사용 감지 테스트
# 1. 로그인하여 토큰 발급
TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -d '{"apiKey":"test"}' | jq -r '.token')

# 2. 토큰 갱신
NEW_TOKEN=$(curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Authorization: Bearer $TOKEN" | jq -r '.token')

# 3. 이전 토큰으로 재사용 시도 (거부되어야 함)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v2/characters/test
# Expected: 401 Unauthorized

# 4. 새 토큰으로 접근 (성공해야 함)
curl -H "Authorization: Bearer $NEW_TOKEN" http://localhost:8080/api/v2/characters/test
# Expected: 200 OK

# 5. 메트릭 확인
curl http://localhost:8080/actuator/metrics/token.security.reuse_detected
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **토큰 재사용 감지 없음** | 탈취된 토큰 무제한 사용 | Token UUID + Redis 검증 |
| **토큰 = 세션 ID** | 재사용 감지 불가 | 별도 Token ID 사용 |
| **로그아웃 후 토큰 유효** | 보안 사고 확대 | 로그아웃 시 토큰 ID 삭제 |
| **IP 변경 미감지** | 토큰 공유 탐지 불가 | 마지막 IP 추적 |

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 27
- [OWASP JSON Web Token (JWT) Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)

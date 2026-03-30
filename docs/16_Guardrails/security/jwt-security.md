---
id: GR-SEC-001
category: security
severity: critical
keywords: [JWT, Secret, Token, Fingerprint, API Key]
---

# JWT Security Best Practices

## DON'T (안티패턴)

### 1. 하드코딩된 비밀키 사용
```java
// Bad (절대 금지)
String secret = "my-secret-key";

// Bad (환경별 구분 없음)
@Value("${jwt.secret}")
private String secret;  // 모든 환경에서 같은 값
```

### 2. JWT에 API Key 포함
```java
// Bad (유출 위험)
String token = Jwts.builder()
    .claim("apiKey", userApiKey)  // JWT는 클라이언트에 노출됨
    .compact();
```

### 3. 짧은 Secret Key 사용
```java
// Bad (32자 미만)
String secret = "short-secret";  // HMAC-SHA256에 취약
```

### 4. Production에서 기본 Secret 사용
```java
// Bad (환경 구분 없음)
String secret = "dev-secret-key-for-development-only";
// Production 환경에서도 사용되면 보안 취약
```

### 5. Deprecated API 사용
```java
// Bad (JJWT 0.12.x 이전)
Jwts.parserBuilder()
    .setSigningKey(key)
    .build()
    .parseClaimsJws(token);
```

### 6. 장기간 유효한 Access Token
```java
// Bad (24시간 유효)
long accessTokenValidity = 86400 * 1000;  // 24시간
```

## DO (베스트 프랙티스)

### 1. 환경 변수 + Fail-Fast 검증
```java
// Good
public JwtTokenProvider(
    @Value("${auth.jwt.secret}") String secret,
    Environment environment) {

    // 1. 환경변수 placeholder 감지
    if (secret.contains("${")) {
        throw new IllegalStateException("JWT_SECRET not set");
    }

    // 2. 빈 값 감지
    if (secret.isBlank()) {
        throw new IllegalStateException("JWT_SECRET is blank");
    }

    // 3. Production 환경에서 기본 값 사용 거부
    if (isProduction && secret.startsWith("dev-secret")) {
        throw new IllegalStateException("Default secret not allowed in prod");
    }

    // 4. 최소 길이 검증 (HS256 = HMAC-SHA256)
    if (secret.length() < 32) {
        throw new IllegalStateException("Secret must be >= 32 chars");
    }
}
```

### 2. Fingerprint로 API Key 식별
```java
// Good (Fingerprint만 포함)
String fingerprint = hmacSha256(serverSecret, userApiKey);
String token = Jwts.builder()
    .claim("fgp", fingerprint)  // 복원 불가능한 해시만 포함
    .compact();

// API Key는 Redis 세션에만 저장
redisTemplate.opsForHash().put(sessionId, "apiKey", userApiKey);
```

### 3. JJWT 0.12.x 최신 API
```java
// Good
Jws<Claims> jws = Jwts.parser()
    .verifyWith(secretKey)           // 공개키 설정
    .build()                         // Parser 빌드
    .parseSignedClaims(token);       // 서명 검증 + 파싱
```

### 4. 환경별 토큰 만료 정책
```java
// application.yml
auth:
  jwt:
    access-token-validity: 900000   # 15분 (Production)
    refresh-token-validity: 604800000 # 7일 (Production)
```

| 환경 | Access Token | Refresh Token | 이유 |
|------|-------------|---------------|------|
| **Production** | 15분 | 7일 | 유출 시 영향 제한 |
| **Staging** | 1시간 | 1일 | 테스트 편의성 |
| **Development** | 24시간 | 30일 | 개발 편의성 |

### 5. Token Reuse Detection
```java
// Good (Redis 세션에서 토큰별 UUID 관리)
String tokenId = UUID.randomUUID().toString();
redisTemplate.opsForValue().set(
    "session:" + sessionId + ":token",
    tokenId,
    expiration
);

// 토큰 재사용 감지
String storedTokenId = redisTemplate.opsForValue()
    .get("session:" + sessionId + ":token");
if (!tokenId.equals(storedTokenId)) {
    throw new TokenReusedException("Token already used");
}
```

### 6. 다층 JWT 보안 (Defense in Depth)

| 계층 | 보안 조치 | 코드 위치 |
|------|----------|----------|
| **Validation** | Secret Key 길이 검증 (>= 32 chars) | `validateSecretKeyForProduction()` |
| **Environment** | Production 환경에서 기본 secret 사용 거부 | `validateSecretKeyForProduction()` |
| **Signature** | HS256 알고리즘 서명 검증 | `parseSignedClaims()` |
| **Expiration** | 토큰 만료 시간 검증 | `Jwts.parser().verifyWith()` |
| **Session** | Redis 세션 검증 (토큰 폐기) | `SessionService.validate()` |
| **Fingerprint** | HMAC-SHA256 기반 키 무결성 검증 | `FingerprintGenerator.verify()` |

## Monitoring & Alerts

```prometheus
# JWT Secret 검증 실패
ALERT JWTSecretValidationFailed
  IF rate(jwt_secret_validation_failed_total[5m]) > 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "JWT Secret validation failed at startup",
    description = "Application will not start. Check JWT_SECRET environment variable."
  }

# 토큰 재사용 감지
ALERT JWTTokenReuseDetected
  IF rate(jwt_token_reuse_detected_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "JWT Token reuse detected",
    description = "Possible token theft or replay attack"
  }
```

## Verification Commands

```bash
# 1. 하드코딩된 시크릿 검색
grep -r "secret.*=" --include="*.java" --include="*.yml" | grep -v "env\|placeholder"

# 2. JWT Secret 길이 확인
echo $JWT_SECRET | wc -c

# 3. JWT에 API Key 포함 확인
grep -r "claim.*apiKey" src/main/kotlin/

# 4. Fingerprint 사용 확인
grep -r "claim.*fgp" src/main/kotlin/

# 5. Deprecated API 사용 확인
grep -r "parserBuilder\|parseClaimsJws" src/main/kotlin/
```

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 27
- P0 #238 (2025-12) - Weak JWT secret caused authentication bypass

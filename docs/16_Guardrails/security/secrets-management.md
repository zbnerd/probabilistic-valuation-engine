---
id: GR-SEC-005
category: security
severity: critical
keywords: [Secrets, Environment, Jasypt, Rotation, Vault, Docker]
---

# Secrets Management

## DON'T (안티패턴)

### 1. application.yml에 평문 비밀
```yaml
# Bad (평문 비밀키)
spring:
  datasource:
    password: MySecretPassword123!
```

### 2. Git에 커밋된 시크릿
```bash
# Bad (Git 히스토리에 영구 보관)
git add application-prod.yml
git commit -m "Add production config"
```

### 3. 하드코딩된 시크릿
```java
// Bad
private static final String API_KEY = "live_abcd1234efgh5678";
```

### 4. 모든 환경에서 같은 시크릿 사용
```java
// Bad (환경별 구분 없음)
String jwtSecret = "shared-secret-for-all-envs";
```

### 5. 시크릿 로테이션 없음
```java
// Bad (영구 사용)
private static final String JWT_SECRET = "permanent-secret";
```

## DO (베스트 프랙티스)

### 1. 환경 변수 우선순위 (12-Factor App)
```
1. 환경 변수 (최우선)
2. Docker Secrets (/run/secrets/)
3. External Secrets Manager (AWS Secrets Manager, HashiCorp Vault)
4. application-{profile}.yml (암호화된 값만)
5. application.yml (기본값만, 개발용)
```

### 2. application.yml 암호화 (Jasypt)
```yaml
# Good (Jasypt 암호화)
spring:
  datasource:
    password: ENC(encrypted_password_here)
jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD}  # 환경 변수에서 복호화 키
```

### 3. Docker Compose Secrets
```yaml
# docker-compose.yml
services:
  app:
    secrets:
      - db_password
      - jwt_secret
    environment:
      - SPRING_DATASOURCE_PASSWORD_FILE=/run/secrets/db_password
      - AUTH_JWT_SECRET_FILE=/run/secrets/jwt_secret
secrets:
  db_password:
    file: ./secrets/db_password.txt
  jwt_secret:
    file: ./secrets/jwt_secret.txt
```

### 4. Kubernetes Secrets
```yaml
# k8s/deployment.yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
stringData:
  db-password: ${DB_PASSWORD}
  jwt-secret: ${JWT_SECRET}

---
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: app
        env:
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: app-secrets
              key: db-password
```

### 5. Secrets Manager 통합 (AWS)
```java
// Good (AWS Secrets Manager)
@Configuration
public class AwsSecretsConfig {

    @Bean
    public DataSource dataSource() {
        String secret = secretsManagerClient.getSecretValue("prod/db/password");
        return DataSourceBuilder.create()
            .password(secret)
            .build();
    }
}
```

### 6. 시크릿 로테이션 전략

| 자산 | 주기 | 절차 |
|------|------|------|
| **JWT Secret** | 90일 | 1. 새 secret 배포, 2. 이중 기간(7일) 운영, 3. 이전 secret 만료 |
| **Database Password** | 180일 | 1. DB 비밀번호 변경, 2. 앱 배포, 3. 재시작 |
| **API Keys** | 365일 | 1. 새 키 발급, 2. 롤오버 기간, 3. 이전 키 폐기 |

### 7. 이중 기간 운영 (Rotation)
```java
// Good (여러 secret 지원)
public class JwtTokenProvider {
    private final List<String> secrets;  // [old, new]

    public String generateToken(String sessionId, String fingerprint) {
        String currentSecret = secrets.get(secrets.size() - 1);  // 최신 secret
        return Jwts.builder()
            .signWith(Keys.hmacShaKeyFor(currentSecret.getBytes()))
            .compact();
    }

    public Claims parseToken(String token) {
        // 모든 secret으로 검증 시도 (이중 기간 지원)
        for (String secret : secrets) {
            try {
                return Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            } catch (JwtException e) {
                // 다음 secret 시도
            }
        }
        throw new JwtException("Invalid token");
    }
}
```

## Anti-Patterns Summary

| Anti-Pattern | Risk | Solution |
|--------------|------|----------|
| **Git에 시크릿 커밋** | 영구 유출 | git-secrets hooks |
| **평문 application.yml** | 로그/백업 노출 | Jasypt 암호화 |
| **하드코딩** | 소스 코드 노출 | 환경 변수 |
| **공유 시크릿** | 한꺼번에 타격 | 환경별 분리 |
| **로테이션 없음** | 장기 유출 위험 | 주기적 교체 |

## Monitoring & Alerts

```prometheus
# 시크릿 검증 실패
ALERT SecretValidationFailed
  IF rate(secret_validation_failed_total[5m]) > 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "Secret validation failed at startup",
    description = "Application will not start. Check environment variables."
  }

# 오래된 시크릿 (180일 이상)
ALERT StaleSecretDetected
  IF time() - secret_last_rotation_timestamp_seconds > 15552000
  SEVERITY warning

  ANNOTATIONS {
    summary = "Secret rotation overdue",
    description = "Rotate secrets immediately"
  }
```

## Verification Commands

```bash
# 1. 하드코딩된 시크릿 검색
grep -r "secret.*=" --include="*.java" --include="*.yml" | grep -v "env\|placeholder"

# 2. 평문 비밀 검색
grep -r "password.*:" --include="*.yml" src/main/resources/ | grep -v "ENC("

# 3. Git 히스토리 시크릿 검색
git log --all --full-history --source -- "*password*" "*secret*"

# 4. 환경 변수 확인
echo $JWT_SECRET | wc -c
```

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 32
- NIST SP 800-53 - SC-12: Cryptographic Key Establishment and Management

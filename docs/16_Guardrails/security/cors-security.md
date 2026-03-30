---
id: GR-SEC-002
category: security
severity: critical
keywords: [CORS, Wildcard, CSRF, Origin, AllowCredentials]
---

# CORS Security Hardening

## DON'T (안티패턴)

### 1. 와일드카드와 Credentials 조합 (치명적)
```java
// Bad (CSRF 취약점)
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);  // 치명적 조합
```

### 2. 환경별 구분 없는 오리진 설정
```java
// Bad (모든 환경에서 동일)
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
    private List<String> allowedOrigins = List.of("*");  // 위험
    private boolean allowCredentials = true;
}
```

### 3. 런타임 오리진 검증 없음
```java
// Bad (Spring 설정만으로 검증)
@Bean
public CorsFilter corsFilter() {
    // 설정된 오리진만 검증, 런타임 검증 없음
    return new CorsFilter(source);
}
```

### 4. HTTP 오리진 사용 (Production)
```java
// Bad (HTTPS가 아님)
List<String> allowedOrigins = List.of("http://maplestory.com");
```

## DO (베스트 프랙티스)

### 1. 환경별 명시적 오리진
```java
// Good
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
    @NotEmpty
    private List<@ValidCorsOrigin String> allowedOrigins;
    private boolean allowCredentials = true;
    private long maxAge = 3600;
}
```

### 2. 3단계 오리진 검증

#### 1단계: 시작 시 포맷 검증
```java
@PostConstruct
public void validateOnStartup() {
    for (String origin : allowedOrigins) {
        // URL 형식 검증
        if (!isValidUrl(origin)) {
            throw new IllegalStateException("Invalid origin: " + origin);
        }
        // 프로토콜 검증 (HTTPS 권장)
        if (isProduction && !origin.startsWith("https://")) {
            log.warn("HTTP origin in production: {}", origin);
        }
        // 금지 패턴 검증
        if (origin.contains("*") || origin.contains("..")) {
            throw new IllegalStateException("Dangerous origin pattern: " + origin);
        }
    }
}
```

#### 2단계: 감사 로그
```java
log.info("[CORS-Config] Allowed origins: {}", allowedOrigins);
```

#### 3단계: 런타임 헤더 검증
```java
@Bean
public CorsValidationFilter corsValidationFilter() {
    return new CorsValidationFilter(validator, executor, allowedOrigins);
}
```

### 3. application.yml 명시적 설정
```yaml
# application-prod.yml
cors:
  allowed-origins:
    - https://maplestory.com
    - https://api.maplestory.com
  allow-credentials: true
  max-age: 3600

# application-local.yml
cors:
  allowed-origins:
    - http://localhost:3000
    - http://localhost:8080
  allow-credentials: true
  max-age: 3600
```

### 4. Spring Security와 통합
```java
// Good (SecurityConfig에서 CORS 설정)
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
    return http.build();
}

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of("https://maplestory.com"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

### 5. CORS vs CSP (Defense in Depth)

| 특성 | CORS | CSP |
|------|------|-----|
| **목적** | Cross-Origin 요청 제어 | 리소스 로딩 제어 |
| **서버 설정** | HTTP 응답 헤더 | HTTP 응답 헤더 |
| **클라이언트** | 브라우저 자동 처리 | 브라우저 자동 처리 |
| **우회 가능성** | 없음 (브라우저 강제) | 없음 (브라우저 강제) |

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **Wildcard + Credentials** | CSRF 취약점 | 명시적 오리진 목록 |
| **HTTP in Production** | MITM 취약 | HTTPS 강제 |
| **No Runtime Validation** | 설정 오류 조용 실패 | CorsValidationFilter 추가 |
| **Environment Blindness** | Dev 설정이 Prod로 | 환경별 분리 |

## Monitoring & Alerts

```prometheus
# CORS 위반 요청
ALERT CORSViolationDetected
  IF rate(cors_validation_failed_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "CORS validation failed",
    description = "Possible CSRF attack attempt"
  }

# Production에서 HTTP 오리진 사용
ALERT HTTPOriginInProduction
  IF cors_origin_protocol{env="prod"} == "http"
  SEVERITY critical

  ANNOTATIONS {
    summary = "HTTP origin detected in production",
    description = "Downgrade to HTTPS immediately"
  }
```

## Verification Commands

```bash
# 1. 와일드카드 CORS 검색
grep -r "setAllowedOriginPatterns" src/main/kotlin/ | grep "\\\*"

# 2. Credentials와 와일드카드 조합 확인
grep -A2 "setAllowedOriginPatterns.*\*" src/main/kotlin/ | grep "setAllowCredentials.*true"

# 3. HTTP 오리진 확인
grep -r "http://.*\.com" src/main/resources/application*.yml

# 4. CORS 헤더 테스트
curl -I -H "Origin: https://evil.com" http://localhost:8080/api/v2/characters/test

# 5. 오리진 검증 로그 확인
grep "CORS-Config" logs/app.log
```

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 29
- Issue #21, #172 - CORS Wildcard Security Issue

---
id: GR-SEC-010
category: security
severity: critical
keywords: [CSP, XSS, ContentSecurityPolicy, script-src, style-src]
---

# Content Security Policy (CSP) Configuration

## DON'T (안티패턴)

### 1. CSP 헤더 미설정
```java
// Bad: XSS 공격에 취약
http.headers(headers -> headers
    // CSP 설정 없음
);
```

### 2. unsafe-inline 과도 사용
```java
// Bad: 모든 인라인 스크립트 허용
.contentSecurityPolicy(csp -> csp
    .policyDirectives("script-src 'self' 'unsafe-inline' 'unsafe-eval';")
)
```

### 3. 와일드카드 소스 허용
```java
// Bad: 모든 도메인에서 리소스 로드 허용
.contentSecurityPolicy(csp -> csp
    .policyDirectives("default-src *;")
)
```

### 4. frame-ancestors 미설정
```java
// Bad: Clickjacking 공격에 취약
.contentSecurityPolicy(csp -> csp
    .policyDirectives("frame-ancestors 'self';")  // 'none'이 아님
)
```

## DO (베스트 프랙티스)

### 1. Spring Security 6.x CSP 설정
```java
// Good: 엄격한 CSP 정책
http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives(
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: https:; " +
            "font-src 'self'; " +
            "connect-src 'self'; " +
            "frame-ancestors 'none'; " +
            "form-action 'self'; " +
            "base-uri 'self';"
        )
    )
);
```

### 2. 환경별 CSP 분리
```java
// Good: 개발 환경에서는 Report-Only 모드
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    if (isDevelopment) {
        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp
                .policyDirectives("...")
                .reportOnly(true)  // 위반 시 차단하지 않고 리포트만
            )
        );
    } else {
        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp
                .policyDirectives("...")
                // reportOnly 미설정 = Enforce 모드
            )
        );
    }
    return http.build();
}
```

### 3. CSP Directive 설명

| 지시어 | 값 | 위협 방지 |
|--------|-----|----------|
| `default-src` | `'self'` | 모든 리소스의 기본 출처 제한 |
| `script-src` | `'self' 'unsafe-inline' 'unsafe-eval'` | XSS 스크립트 주입 방지 |
| `style-src` | `'self' 'unsafe-inline'` | CSS 주입 방지 |
| `img-src` | `'self' data: https:` | 이미지 XSS 방지 |
| `connect-src` | `'self'` | CSRF/Ajax 요청 제한 |
| `frame-ancestors` | `'none'` | Clickjacking 방지 |
| `form-action` | `'self'` | 폼 제출 위변조 방지 |
| `base-uri` | `'self'` | Base 태그 주입 방지 |

### 4. Nonce/SHA 기반 CSP (개선 방향)
```java
// Future: Nonce 기반 CSP (unsafe-inline 제거)
@ControllerAdvice
public class CsrfControllerAdvice {

    @GetMapping("/api/**")
    public String addNonce(Model model) {
        String nonce = UUID.randomUUID().toString();
        model.addAttribute("cspNonce", nonce);
        return "index";
    }
}

// SecurityConfig
http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives("script-src 'self' 'nonce-{cspNonce}';")
    )
);
```

### 5. CSP 위반 모니터링
```java
// CSP 위반 리포트 엔드포인트
@PostMapping("/csp-report")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void handleCspReport(@RequestBody String report) {
    log.warn("CSP Violation: {}", report);

    // Prometheus 메트릭 기록
    meterRegistry.counter("csp.violations.total").increment();

    // Discord/Webhook 알림 (심각한 위반 시)
    if (isSevereViolation(report)) {
        alertService.sendSecurityAlert("CSP Violation Detected", report);
    }
}
```

### 6. CSP 헤더 테스트
```bash
# 1. CSP 헤더 확인
curl -I http://localhost:8080/api/v2/characters/test | grep -i "content-security-policy"

# 2. frame-ancestors 확인 (Clickjacking 방지)
curl -I http://localhost:8080 | grep -i "x-frame-options"

# 3. CSP 위반 테스트
curl -X POST http://localhost:8080/api/v2/characters \
  -H "Content-Type: application/json" \
  -d '{"ign":"<script>alert(1)</script>"}'

# 4. 개발 환경 Report-Only 확인
curl -I http://localhost:8080 | grep -i "content-security-policy-report-only"
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **CSP 미설정** | XSS 방어 계층 부족 | 최소한 `default-src 'self'` 설정 |
| **Wildcard sources** | 모든 도메인에서 리소스 로드 가능 | 구체적 도메인 명시 |
| **missing frame-ancestors** | Clickjacking 공격 가능 | `frame-ancestors 'none'` 설정 |
| **unsafe-* 과도 사용** | CSP 효과 감소 | Nonce/SHA 기반 정책으로 전환 |

## Monitoring & Alerts

```prometheus
# CSP 위반 감지
ALERT CSPViolationDetected
  IF rate(csp_violations_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "CSP policy violation detected",
    description = "Possible XSS attack attempt"
  }

# CSP 헤더 누락
ALERT CSPHeaderMissing
  IF http_response_headers{name="Content-Security-Policy"} == 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "CSP header not configured",
    description = "XSS vulnerability risk increased"
  }
```

## Verification Commands

```bash
# 1. CSP 설정 확인
grep -A 10 "contentSecurityPolicy" src/main/kotlin/**/SecurityConfig.java

# 2. unsafe-inline 사용 확인
grep -r "unsafe-inline\|unsafe-eval" src/main/kotlin/**/SecurityConfig.java

# 3. frame-ancestors 설정 확인
grep -r "frame-ancestors" src/main/kotlin/**/SecurityConfig.java

# 4. 와일드카드 소스 확인
grep -r "default-src \*\|script-src \*" src/main/kotlin/**/SecurityConfig.java

# 5. CSP 리포트 엔드포인트 확인
grep -r "csp-report\|/csp-report" src/main/kotlin/
```

## CSP vs CORS

| 특성 | CSP | CORS |
|------|-----|------|
| **목적** | 리소스 로딩 제어 | Cross-Origin 요청 제어 |
| **서버 설정** | HTTP 응답 헤더 | HTTP 응답 헤더 |
| **클라이언트** | 브라우저 자동 처리 | 브라우저 자동 처리 |
| **우회 가능성** | 없음 (브라우저 강제) | 없음 (브라우저 강제) |

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 28
- [OWASP CSP Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html)
- [Spring Security 6.x Documentation](https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html#headers-content-security-policy)

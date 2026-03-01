---
id: GR-SEC-006
category: security
severity: critical
keywords: [Filter, CGLIB, @Bean, SecurityContext, OncePerRequestFilter]
---

# Spring Security 6.x Filter Best Practice

## DON'T (안티패턴)

### 1. @Component로 Filter 등록 (CGLIB 문제)
```java
// Bad (@Component 사용 시 CGLIB 프록시 문제 발생)
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // java.lang.NullPointerException: Cannot invoke "Log.isDebugEnabled()"
    // because "this.logger" is null
}
```

### 2. 기존 SecurityContext 재사용
```java
// Bad (동시성 문제)
SecurityContextHolder.getContext().setAuthentication(auth);
```

### 3. Filter 중복 등록
```java
// Bad (서블릿 컨테이너에도 자동 등록됨)
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter() {
    return new JwtAuthenticationFilter(...);
}
// FilterRegistrationBean 누락 -> 필터 2회 실행
```

### 4. SecurityFilterChain에 필터 추가 누락
```java
// Bad (필터가 Security Chain에 추가되지 않음)
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter() {
    return new JwtAuthenticationFilter(...);
}

@Bean
public SecurityFilterChain filterChain(HttpSecurity http) {
    // jwtAuthenticationFilter 추가 누락
    return http.build();
}
```

## DO (베스트 프랙티스)

### 1. @Bean으로 수동 등록
```java
// Good (@Component 제거 -> SecurityConfig에서 @Bean 등록)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider provider;
    private final SessionService service;
    private final FingerprintGenerator generator;

    // @Component 제거
}
```

### 2. Filter Bean 등록 패턴 (Context7 공식)
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. Filter Bean 직접 등록 (생성자 주입)
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider provider,
            SessionService service,
            FingerprintGenerator generator) {
        return new JwtAuthenticationFilter(provider, service, generator);
    }

    // 2. 서블릿 컨테이너 중복 등록 방지
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);  // 서블릿 컨테이너 등록 비활성화
        return registration;
    }

    // 3. SecurityFilterChain에 필터 추가
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter filter) throws Exception {
        http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### 3. SecurityContext 새로 생성 (Thread-Safe)
```java
// Good (새 컨텍스트 생성 -> Thread-Safe)
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);
```

### 4. FilterRegistrationBean 필요성

| 시나리오 | 결과 |
|---------|------|
| `@Bean`만 등록 | Spring Boot가 서블릿 컨테이너에도 자동 등록 -> 필터 2회 실행 |
| `FilterRegistrationBean.setEnabled(false)` | Spring Security만 필터 관리 -> 1회 실행 |

### 5. 보안 헤더 설정 (Spring Security 6.x Lambda DSL)
```java
http.headers(headers -> headers
    .frameOptions(frame -> frame.deny())           // Clickjacking 방지
    .contentTypeOptions(Customizer.withDefaults()) // MIME 스니핑 방지
    .httpStrictTransportSecurity(hsts -> hsts      // HSTS
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
    .contentSecurityPolicy(csp -> csp              // CSP
        .policyDirectives(
            "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; "
            + "font-src 'self'; "
            + "connect-src 'self'; "
            + "frame-ancestors 'none'; "
            + "form-action 'self'; "
            + "base-uri 'self';"
        )
    )
);
```

## CGLIB 프록시 문제 (P0 #238)

**Root Cause:** `OncePerRequestFilter`를 상속한 필터에 `@Component`를 붙이면 CGLIB 프록시 생성 시 부모 클래스의 `logger` 필드가 초기화되지 않아 NPE 발생

**Evidence:** [P0 Report](../../../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md) Section 4.2

```java
// 문제 발생 경로:
@Component                          // Spring이 CGLIB 프록시 생성
    ↓
public class JwtFilter extends ...   // 프록시가 상속
    ↓
private final Log logger = ...      // 프록시에는 logger 초기화 안 됨
    ↓
logger.isDebugEnabled()              // NPE 발생
```

## Anti-Patterns Summary

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **@Component on Filter** | CGLIB NPE | @Bean 등록 |
| **Context Reuse** | Thread-Safety | createEmptyContext() |
| **No FilterRegistrationBean** | 2회 실행 | setEnabled(false) |
| **Missing addFilterBefore** | 필터 미작동 | SecurityFilterChain에 추가 |

## Verification Commands

```bash
# 1. @Component가 있는 Filter 확인
grep -r "@Component" src/main/java/**/filter/ | grep "Filter"

# 2. FilterRegistrationBean 확인
grep -r "FilterRegistrationBean" src/main/java/

# 3. SecurityFilterChain addFilterBefore 확인
grep -A5 "addFilterBefore" src/main/java/**/SecurityConfig.java

# 4. CGLIB 관련 NPE 로그 확인
grep -i "NullPointerException.*logger" logs/
```

## 출처
- [docs/03_Technical_Guides/infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 18
- P0 #238 (2025-12) - CGLIB proxy NPE in Filter caused authentication bypass

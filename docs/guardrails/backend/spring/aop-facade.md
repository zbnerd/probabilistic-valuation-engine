---
id: GR-003
category: backend/spring
severity: critical
keywords: [AOP, Facade, Self-Invocation, CGLIB, Spring Security, Filter, OncePerRequestFilter]
---
# AOP & Facade Pattern & Spring Security Filter

## DON'T (안티패턴)

### 1. Self-Invocation (AOP 무시)
```java
// Bad (AOP가 작동하지 않음)
@Service
@RequiredArgsConstructor
public class GameService {
    @Trace
    public void process() {
        validate();  // AOP 미작동 (직접 호출)
    }

    @Trace
    private void validate() { ... }
}
```

### 2. OncePerRequestFilter에 @Component
```java
// Bad (CGLIB 프록시 문제 -> NPE)
@Component  // 제거해야 함!
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // java.lang.NullPointerException: Cannot invoke "Log.isDebugEnabled()"
    // because "this.logger" is null
}
```

### 3. SecurityContext 재사용
```java
// Bad (동시성 문제)
SecurityContextHolder.getContext().setAuthentication(auth);
```

### 4. 민감 데이터 Record 기본 toString()
```java
// Bad (API Key 평문 노출)
public record LoginRequest(String apiKey, String userIgn) {}
// 로그: LoginRequest[apiKey=live_abcd1234efgh5678, userIgn=닉네임]
```

### 5. Filter를 Servlet Container에 중복 등록
```java
// Bad (필터 2회 실행)
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(...) {
    return new JwtAuthenticationFilter(...);
}
// FilterRegistrationBean 누락 -> Spring Boot가 서블릿 컨테이너에도 자동 등록
```

### 6. API Key를 JWT에 포함
```java
// Bad (JWT는 클라이언트에 노출됨)
String jwt = Jwts.builder()
    .claim("apiKey", apiKey)  // 절대 금지!
    .compact();
```

## DO (베스트 프랙티스)

### 1. Facade Pattern (AOP Self-Invocation 해결)
```java
// Good (Facade에서 AOP 메서드 호출)
@Facade
@RequiredArgsConstructor
public class GameCharacterFacade {
    private final GameCharacterService gameCharacterService;
    private final DistributedLockStrategy lockStrategy;

    @Trace
    public CharacterDto process(String ign) {
        return lockStrategy.executeWithLock(
            "character:" + ign,
            () -> gameCharacterService.calculate(ign)  // AOP 정상 작동
        );
    }
}

@Service
@RequiredArgsConstructor
public class GameCharacterService {
    // 비즈니스 로직 + 트랜잭션
    public CharacterDto calculate(String ign) { ... }
}
```

**Facade 역할:**
- 분산 락 획득 및 해제
- 서비스 간 흐름 제어 (Orchestration)
- 락 범위 보장 (Lock -> Transaction -> Unlock)

**Service 역할:**
- 비즈니스 로직
- 트랜잭션 경계

### 2. Filter Bean 수동 등록 (@Bean)
```java
// Good (@Component 제거)
@RequiredArgsConstructor  // @Component 제거
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider provider;
    private final SessionService service;
    // ...
}

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. Filter Bean 직접 등록
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
// Good
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);
```

### 4. 민감 데이터 마스킹 (toString() 오버라이드)
```java
// Good (toString() 오버라이드)
public record LoginRequest(String apiKey, String userIgn) {
    @Override
    public String toString() {
        return "LoginRequest[" +
                "apiKey=" + maskApiKey(apiKey) +
                ", userIgn=" + userIgn + "]";
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
// 로그: LoginRequest[apiKey=live****5678, userIgn=닉네임]
```

**마스킹 대상 필드:**
- API Key, Secret Key
- 비밀번호, 토큰
- 개인정보 (주민번호, 전화번호 등)

### 5. API Key 저장: Redis + Fingerprint
```java
// Good (Redis 세션에만 저장)
// 1. Redis 세션에 API Key 저장
redisTemplate.opsForHash().put("session:" + sessionId, "apiKey", apiKey);

// 2. HMAC-SHA256으로 변환하여 JWT에 저장
String fingerprint = HMAC-SHA256(serverSecret, apiKey);
String jwt = Jwts.builder()
    .claim("fingerprint", fingerprint)  // 변환된 값만 저장
    .compact();
```

### 6. Spring Security 보안 헤더 설정
```java
// Good (Spring Security 6.x Lambda DSL)
http.headers(headers -> headers
    .frameOptions(frame -> frame.deny())           // Clickjacking 방지
    .contentTypeOptions(Customizer.withDefaults()) // MIME 스니핑 방지
    .httpStrictTransportSecurity(hsts -> hsts
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
    .contentSecurityPolicy(csp -> csp
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

### 7. Swagger UI Security 설정
```java
// Good (Swagger UI 엔드포인트 permitAll)
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
    .anyRequest().authenticated()
);
```

### 8. Logging 레벨 엄격 구분
```java
// Good
@Slf4j
public class GameService {
    public void process() {
        log.info("Process started: ign={}", ign);    // 주요 지점
        log.debug("Checking cache: key={}", key);   // 장애 추적
    }

    public void handleError(Exception e) {
        log.error("Processing failed: ign={}, error={}", ign, e.getMessage(), e);
    }
}
```

## 출처
- infrastructure.md Section 7: AOP & Facade Pattern
- infrastructure.md Section 18: Spring Security 6.x Filter Best Practice
- infrastructure.md Section 19: Security Best Practices
- P0 Incident #238: CGLIB Proxy NPE in Filter
- P0 Incident #241: Self-Invocation Bug

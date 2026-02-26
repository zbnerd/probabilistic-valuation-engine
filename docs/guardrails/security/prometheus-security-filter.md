---
id: GR-SEC-011
category: security
severity: critical
keywords: [Prometheus, Actuator, Metrics, IPWhitelist, SecurityFilter, X-Forwarded-For]
---

# Prometheus Security Filter (Defense in Depth)

## DON'T (안티패턴)

### 1. 단일 계층 보안만 의존
```java
// Bad: Spring Security만으로 방어
http.securityMatcher("/actuator/prometheus")
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
    );
```

### 2. X-Forwarded-For 스푸핑 미검증
```java
// Bad: X-Forwarded-For 헤더를 무조건 신뢰
String clientIp = request.getHeader("X-Forwarded-For");
if (isInternalNetwork(clientIp)) {
    chain.doFilter(request, response);
}
```

### 3. IP 헤더 우회 가능성 무시
```java
// Bad: RemoteAddr만 확인 (프록시 환경에서 우회 가능)
if (!request.getRemoteAddr().equals("127.0.0.1")) {
    response.sendError(403);
}
```

### 4. Rate Limiting 미적용
```java
// Bad: 요청 제한 없음
// 공격자가 무한히 메트릭을 요청 가능
```

## DO (베스트 프랙티스)

### 1. 다층 보안 아키텍처 (Defense in Depth)
```java
// Good: 4계층 보안
@Component
public class PrometheusSecurityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        if (!requestUri.startsWith("/actuator/prometheus") &&
            !requestUri.startsWith("/actuator/metrics")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);

        // Layer 1: IP Whitelist (Network Level)
        if (!isInternalNetwork(clientIp)) {
            log.warn("[Prometheus-Security] External IP rejected: {}", clientIp);
            response.sendError(HttpStatus.FORBIDDEN.value(), "Access denied");
            meterRegistry.counter("prometheus.security.rejected",
                "reason", "external_ip").increment();
            return;
        }

        // Layer 2: X-Forwarded-For Validation (Edge Level)
        if (isSpoofedHeader(request, clientIp)) {
            log.warn("[Prometheus-Security] X-Forwarded-For spoofing detected: {}", clientIp);
            response.sendError(HttpStatus.FORBIDDEN.value(), "Invalid headers");
            meterRegistry.counter("prometheus.security.rejected",
                "reason", "spoofed_header").increment();
            return;
        }

        // Layer 3: Rate Limiting (Business Logic Level)
        String rateLimitKey = "prometheus:ratelimit:" + clientIp;
        if (rateLimitExceeded(rateLimitKey)) {
            log.warn("[Prometheus-Security] Rate limit exceeded: {}", clientIp);
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests");
            meterRegistry.counter("prometheus.security.rejected",
                "reason", "rate_limit").increment();
            return;
        }

        // Layer 4: Security Context Check (Application Level)
        // Spring Security Role Check가 이후에 수행됨

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        // 신뢰할 수 있는 프록시에서만 X-Forwarded-For 사용
        String xForwardedFor = request.getHeader("X-Forwarded-For");

        if (xForwardedFor != null && isTrustedProxy(request.getRemoteAddr())) {
            // 첫 번째 IP가 원본 클라이언트
            return xForwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private boolean isSpoofedHeader(HttpServletRequest request, String clientIp) {
        // X-Forwarded-For가 있지만 RemoteAddr이 신뢰 프록시가 아니면 스푸핑
        String xForwardedFor = request.getHeader("X-Forwarded-For");

        if (xForwardedFor != null && !isTrustedProxy(request.getRemoteAddr())) {
            return true;
        }

        return false;
    }

    private boolean isInternalNetwork(String ip) {
        // localhost
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("localhost")) {
            return true;
        }

        // 사설 네트워크
        try {
            InetAddress addr = InetAddress.getByName(ip);

            // 10.0.0.0/8
            if (ip.startsWith("10.")) {
                return true;
            }

            // 172.16.0.0/12
            if (ip.startsWith("172.")) {
                String[] parts = ip.split("\\.");
                int second = Integer.parseInt(parts[1]);
                if (second >= 16 && second <= 31) {
                    return true;
                }
            }

            // 192.168.0.0/16
            if (ip.startsWith("192.168.")) {
                return true;
            }

        } catch (Exception e) {
            log.error("IP parsing error: {}", ip, e);
            return false;
        }

        return false;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        // Nginx, ALB 등 신뢰할 수 있는 프록시 IP
        return isInternalNetwork(remoteAddr);
    }
}
```

### 2. Spring Security와 통합
```java
// Good: SecurityConfig에서도 명시적 보호
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/actuator/**")
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/actuator/prometheus", "/actuator/metrics").hasRole("ADMIN")
            .anyRequest().denyAll()
        )
        .addFilterBefore(prometheusSecurityFilter, AuthorizationFilter.class);

    return http.build();
}
```

### 3. Rate Limiting 구현
```java
// Good: Redis 기반 Rate Limiting
private boolean rateLimitExceeded(String key) {
    RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);

    // 1초에 10회 요청 제한
    rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.SECONDS);

    // 요청 시도
    return !rateLimiter.tryAcquire(1);
}
```

### 4. Prometheus 메트릭 기록
```java
// Good: 보안 이벤트 메트릭
@Component
public class PrometheusSecurityFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientIp = getClientIp(request);

        if (!isInternalNetwork(clientIp)) {
            meterRegistry.counter("prometheus.security.rejected",
                "reason", "external_ip",
                "ip", clientIp
            ).increment();

            log.warn("[Prometheus-Security] Rejected external IP: {}", clientIp);
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }

        meterRegistry.counter("prometheus.security.accepted",
            "ip", clientIp
        ).increment();

        chain.doFilter(request, response);
    }
}
```

### 5. 신뢰할 수 있는 IP 목록 관리
```yaml
# application.yml
prometheus:
  security:
    trusted-proxies:
      - "127.0.0.1"
      - "::1"
      - "10.0.0.0/8"
      - "172.16.0.0/12"
      - "192.168.0.0/16"
    rate-limit:
      requests-per-second: 10
```

## Monitoring & Alerts

```prometheus
# Prometheus 접근 거부 감지
ALERT PrometheusAccessRejected
  IF rate(prometheus_security_rejected_total[5m]) > 0.1
  SEVERITY warning

  ANNOTATIONS {
    summary = "Prometheus access rejected",
    description = "Possible security probe attempt"
  }

# Rate Limit 초과
ALERT PrometheusRateLimitExceeded
  IF rate(prometheus_security_rejected_total{reason="rate_limit"}[5m]) > 0.05
  SEVERITY warning

  ANNOTATIONS {
    summary = "Prometheus rate limit exceeded",
    description = "High request volume detected"
  }

# 외부 IP 접근 시도
ALERT PrometheusExternalIPAttempt
  IF prometheus_security_rejected_total{reason="external_ip"} > 0
  SEVERITY critical

  ANNOTATIONS {
    summary = "External IP attempting Prometheus access",
    description = "Investigate immediately"
  }
```

## Verification Commands

```bash
# 1. IP Whitelist 테스트
curl -I http://localhost:8080/actuator/prometheus
# Expected: 200 (localhost)

# 2. 외부 IP 거부 테스트
# (VPN이나 외부 네트워크에서)
curl -I http://YOUR_SERVER_IP:8080/actuator/prometheus
# Expected: 403

# 3. X-Forwarded-For 스푸핑 테스트
curl -I -H "X-Forwarded-For: 127.0.0.1" http://YOUR_SERVER_IP:8080/actuator/prometheus
# Expected: 403 (스푸핑 감지)

# 4. Rate Limit 테스트
for i in {1..20}; do
  curl -I http://localhost:8080/actuator/prometheus
done
# Expected: 429 after 10 requests

# 5. 메트릭 확인
curl http://localhost:8080/actuator/metrics/prometheus.security.rejected
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **단일 계층 보안** | 한 계층 우회 시 보안 무력화 | Defense in Depth (4계층) |
| **X-Forwarded-For 신뢰** | 스푸핑으로 우회 가능 | RemoteAddr 검증 |
| **Rate Limit 없음** | DoS 공격 가능 | Redis 기반 제한 |
| **IP 목록 하드코딩** | 환경별 설정 어려움 | ConfigProperties 관리 |

## 출처
- [docs/03_Technical_Guides/security-hardening.md](../../../03_Technical_Guides/security-hardening.md) Section 26
- [Spring Boot Actuator Security](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.security)
- Issue #20, #34 - Prometheus Security Filter

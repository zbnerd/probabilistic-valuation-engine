---
id: GR-SEC-013
category: security
severity: critical
keywords: [IncidentResponse, NIST, MTTR, MTTD, SecurityIncident, RCA]
---

# Security Incident Response Playbook

## DON'T (안티패턴)

### 1. 사고 발생 시 즉시 보고하지 않음
```java
// Bad: 보안 이벤트를 로그만 남기고 알림 없음
log.error("Authentication bypass detected");
```

### 2. 증거 보존하지 않음
```bash
# Bad: 장애 발생 시 로그 즉시 삭제/덮어쓰기
rm -f /var/log/app.log.1
```

### 3. 근본 원인 분석(RCA) 생략
```markdown
## Bad: RCA 없이 핫픽스만 적용
- Fixed: Added @Bean to filter
- No root cause analysis
```

### 4. 재발 방지 대책 없음
```java
// Bad: 동일 문제 반복 가능
public void quickFix() {
    // 증상만 해결, 근본 원인 미해결
}
```

## DO (베스트 프랙티스)

### 1. NIST 기반 사고 대응 수명 주기

```
Phase 1: Preparation (준비)
├── IRT(Incident Response Team) 구성
├── 통신 채널 확보 (Discord #security, On-call)
├── 모니터링/알림 시스템 구축
├── 플레이북 작성
└── 정기 훈련 (Tabletop Exercise)

Phase 2: Detection & Analysis (감지 및 분석)
├── 사고 탐지 시그널 정의
├── 심각도 분류 (P0/P1/P2/P3)
└── 영향도 평가

Phase 3: Containment, Eradication, Recovery (억제, 근절, 복구)
├── 영향 범위 격리
├── 근본 원인 제거
└── 서비스 복구

Phase 4: Post-Incident Activity (사후 활동)
├── RCA 작성
├── 개선 계획 수립
└── 문서 업데이트
```

### 2. 사고 탐지 시그널

| 시그널 | 탐지 방법 | 위험도 | 대응 시간 (MTTR) |
|--------|----------|--------|------------------|
| 비정상적인 로그인 실패 | Prometheus: `auth_login_failed_total` > 100/min | P1 | 4시간 |
| 알 수 없는 IP 접속 | Prometheus: GeoIP mismatch | P2 | 1일 |
| API 응답 시간 급증 | Prometheus: `http_request_duration_seconds` > 5s | P1 | 4시간 |
| 에러율 급증 | Prometheus: `http_server_errors_seconds` > 5% | P0 | 1시간 |
| 의심스러운 로그 | ELK: `/etc/passwd`, `SELECT * FROM` | P0 | 1시간 |

### 3. 증거 수집 절차

```bash
#!/bin/bash
# scripts/security/collect-evidence.sh

EVIDENCE_DIR="/evidence/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE_DIR"

# 1. 애플리케이션 로그
cp /var/log/app.log "$EVIDENCE_DIR/app.log"
chmod 400 "$EVIDENCE_DIR/app.log"

# 2. Nginx 로그
cp /var/log/nginx/access.log "$EVIDENCE_DIR/nginx-access.log"
cp /var/log/nginx/error.log "$EVIDENCE_DIR/nginx-error.log"

# 3. Prometheus 스냅샷
curl http://localhost:9090/api/v1/admin/tsdb/snapshot \
  -o "$EVIDENCE_DIR/prometheus-snapshot.json"

# 4. Redis 백업
redis-cli --rdb "$EVIDENCE_DIR/redis-dump.rdb"

# 5. Git 상태
cd /app
git rev-parse HEAD > "$EVIDENCE_DIR/git-commit.txt"
git diff > "$EVIDENCE_DIR/git-diff.txt"

# 6. JVM Heap Dump
jcmd <pid> GC.heap_dump "$EVIDENCE_DIR/heap-dump.hprof"

# 7. 네트워크 패킷 (장기간 수집 시)
# tcpdump -i eth0 -w "$EVIDENCE_DIR/network.pcap" -G 60 -W 1

echo "Evidence collected: $EVIDENCE_DIR"
```

### 4. 공통 플레이북: 인증 우회

#### 즉시 조치 (0-15분)
```bash
# 1. 영향도 확인
grep "JWT.*valid" /var/log/app.log | tail -100

# 2. 인증 필터 로깅 레벨 상향
curl -X POST http://localhost:8080/actuator/loggers/maple.expectation.global.security \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'

# 3. 공급자 확인
curl http://localhost:8080/actuator/health
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. SecurityConfig 설정
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/protected-endpoint").authenticated()  // 이 설정 누락?

// 2. Filter 등록 순서
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)  // 순서 누락?

// 3. Filter @Component 사용 (CGLIB 문제)
@Component  // 이 어노테이션 제거?
public class JwtAuthenticationFilter extends OncePerRequestFilter
```

#### 해결 및 복구 (60-120분)
```java
// 1. SecurityConfig 수정
.requestMatchers("/api/admin/**").hasRole("ADMIN")  // 명시적 보호

// 2. Filter @Bean 등록으로 변경
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(...) {
    return new JwtAuthenticationFilter(...);
}

// 3. 배포 후 검증
./gradlew test --tests "*Security*"
```

### 5. 공통 플레이북: CORS Misconfiguration

#### 즉시 조치 (0-15분)
```bash
# 1. 현재 CORS 설정 확인
curl -I -H "Origin: https://evil.com" \
  http://localhost:8080/api/v2/characters/test

# 2. 응답 헤더 확인
# Access-Control-Allow-Origin: https://evil.com  <- 문제!

# 3. 긴급 수정: Nginx에서 Origin 차단
nginx -s reload
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. 와일드카드 사용
configuration.setAllowedOriginPatterns(List.of("*"));  // 문제!

// 2. Credentials 허용
configuration.setAllowCredentials(true);  // *와 조합 시 치명적

// 3. 환경별 구분 없음
// 모든 환경에서 동일한 설정
```

#### 해결 및 복구 (60-120분)
```java
// 1. 환경별 설정 분리
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
    @NotEmpty
    private List<@ValidCorsOrigin String> allowedOrigins;
}

// 2. 시작 시 검증
@PostConstruct
public void validateOnStartup() {
    for (String origin : allowedOrigins) {
        if (origin.contains("*")) {
            throw new IllegalStateException("Wildcard not allowed");
        }
    }
}

// 3. 런타임 검증 필터 추가
@Bean
public CorsValidationFilter corsValidationFilter() { ... }
```

### 6. RCA (Root Cause Analysis) 템플릿

```markdown
# Security Incident RCA: [INCIDENT-TITLE]

## 1. Executive Summary
- **Date:** 2026-MM-DD HH:MM KST
- **Duration:** X hours (detection to resolution)
- **Impact:** [description]
- **Root Cause:** [one-line summary]

## 2. Timeline
| Time (KST) | Event |
|------------|-------|
| 10:00 | Alert: authentication failure rate > 50% |
| 10:05 | IRT activated |
| 10:15 | Root cause identified: ... |
| 11:00 | Hotfix deployed |
| 11:15 | Verification complete |
| 12:00 | Normal operation restored |

## 3. Impact Assessment
- **Users Affected:** X
- **Data Compromised:** [Yes/No]
- **Service Downtime:** X minutes
- **Financial Impact:** [if applicable]

## 4. Root Cause Analysis (5 Whys)
1. Why did authentication fail?
   -> Because JWT filter was not processing tokens.

2. Why was JWT filter not processing?
   -> Because filter was registered with @Component (CGLIB proxy).

3. Why was @Component used?
   -> Because Best Practice was not documented.

4. Why was Best Practice not documented?
   -> Because SecurityConfig documentation was incomplete.

5. Why was documentation incomplete?
   -> **Root Cause:** No security documentation review process.

## 5. Immediate Actions (Hotfix)
- [ ] Remove @Component from filter
- [ ] Register filter as @Bean
- [ ] Deploy to production

## 6. Long-term Actions (Prevention)
- [ ] Update CLAUDE.md Section 18 (Filter Bean registration)
- [ ] Add security unit test
- [ ] Implement pre-commit security check

## 7. Lessons Learned
1. Spring Security 6.x Filter registration requires @Bean, not @Component
2. CGLIB proxy causes logger NPE in OncePerRequestFilter subclasses
3. Security documentation should be comprehensive and reviewed

## 8. Follow-up Items
| Item | Owner | Due Date | Status |
|------|-------|----------|--------|
| Documentation update | Security Lead | 2026-MM-DD | Pending |
| Security test enhancement | QA Lead | 2026-MM-DD | Pending |
| Team training | Tech Lead | 2026-MM-DD | Pending |
```

### 7. 커뮤니케이션 프로토콜

#### 내부 커뮤니케이션
| 시점 | 채널 | 내용 | 빈도 |
|------|------|------|------|
| **감지** | Discord #security | 사고 발생, 영향도 | 즉시 |
| **분석** | Discord #security | 진행 상황, 예상 시간 | 15분마다 |
| **복구** | Discord #security | 복구 완료, 후속 조치 | 1시간마다 |
| **종료** | Discord #general | 전체 공지 | 1회 |

#### 공개 타임라인
```
T+0분:   사고 감지, 내부 알림
T+15분:  영향도 파악 완료, IRT 구성
T+30분:  첫 번째 상황 업데이트 (Discord)
T+60분:  대응 방침 결정, 복구 시작
T+120분: 복구 완료, 서비스 정상화
T+24시간: RCA 초안 작성, 내부 공유
T+72시간: RCA 완료, 대외 공개 (필요시)
```

### 8. 메트릭 및 모니터링

```prometheus
# 보안 사고 발생
ALERT SecurityIncidentDetected
  IF security_incident_active == 1
  SEVERITY critical

  ANNOTATIONS {
    summary = "Security incident in progress",
    description = "Check #security-incidents channel"
  }

# MTTR 추적
histogram_quantile(0.95,
  sum(rate(security_incident_duration_seconds_bucket[24h])) by (le, severity)
)

# MTTD 추적
histogram_quantile(0.95,
  sum(rate(security_incident_detection_time_seconds_bucket[24h])) by (le)
)
```

## Verification Commands

```bash
# 1. 증거 수집 스크립트 테스트
bash scripts/security/collect-evidence.sh --dry-run

# 2. IRT 연락처 확인
cat scripts/security/irt-contacts.yaml

# 3. 알림 시스템 테스트
curl -X POST $DISCORD_WEBHOOK -d '{"content": "Security incident test"}'

# 4. 플레이북 문서 확인
ls -la docs/98_Templates/security-*.md
```

## Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **보고 지연** | 대응 시간 증가, 피해 확대 | 즉시 보고 (MTTR 최소화) |
| **증거 미보존** | 근본 원인 파악 불가 | 즉시 백업 |
| **RCA 생략** | 재발 가능 | 5-Whys 분석 |
| **문서 미갱신** | 동일 실제 반복 | CLAUDE.md 업데이트 |

## 출처
- [docs/03_Technical_Guides/security-incident-response.md](../../../03_Technical_Guides/security-incident-response.md)
- [NIST SP 800-61 Rev. 2](https://csrc.nist.gov/publications/detail/sp/800-61/rev-2/final)
- P0 #238, #241, #287 - Security Incidents

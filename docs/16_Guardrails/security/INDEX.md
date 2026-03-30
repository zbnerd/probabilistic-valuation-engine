# Guardrails - Security

## 개요

보안(Security) 관련 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-SEC-001 | [JWT Security Best Practices](jwt-security.md) | critical | JWT, Secret, Token, Fingerprint |
| GR-SEC-002 | [CORS Security Hardening](cors-security.md) | critical | CORS, Wildcard, CSRF, Origin |
| GR-SEC-003 | [Input Validation & Sanitization](input-validation.md) | critical | Injection, PathTraversal, XSS, SQLi |
| GR-SEC-004 | [Sensitive Data Logging Rules](sensitive-data-logging.md) | critical | Logging, Masking, GDPR, PII |
| GR-SEC-005 | [Secrets Management](secrets-management.md) | critical | Secrets, Environment, Jasypt, Rotation |
| GR-SEC-006 | [Spring Security 6.x Filter Best Practice](spring-security-filter.md) | critical | Filter, CGLIB, @Bean, SecurityContext |
| GR-SEC-007 | [API Client Security](api-client-security.md) | warning | WebClient, onErrorResume, onStatus |
| GR-SEC-008 | [OWASP Top 10 Protection](owasp-top10.md) | critical | OWASP, Injection, BrokenAccess, Crypto |
| GR-SEC-009 | [Security Testing Patterns](security-testing.md) | critical | SAST, DAST, ZAP, UnitTest |
| GR-SEC-010 | [Content Security Policy (CSP)](csp-content-security-policy.md) | critical | CSP, XSS, ContentSecurityPolicy, script-src |
| GR-SEC-011 | [Prometheus Security Filter](prometheus-security-filter.md) | critical | Prometheus, Actuator, Metrics, IPWhitelist, X-Forwarded-For |
| GR-SEC-012 | [JWT Token Reuse Detection](token-reuse-detection.md) | warning | JWT, TokenReuse, RedisSession, CompromiseDetection |
| GR-SEC-013 | [Security Incident Response Playbook](incident-response-playbook.md) | critical | IncidentResponse, NIST, MTTR, MTTD, RCA |

## 주요 주제

### JWT Security
- **Secret Key 관리**: 환경 변수 + Fail-Fast 검증
- **API Key 저장**: Redis 세션에만 저장, Fingerprint 사용
- **토큰 만료 정책**: 환경별 다른 TTL

### CORS Security
- **Wildcard 금지**: 와일드카드와 credentials 조합 방지
- **오리진 검증**: 3단계 검증 (시작 시, 감사 로그, 런타임)

### Input Validation
- **Path Variable Injection 방지**: 정규식 검증
- **SQL Injection 방지**: Parameterized Query
- **Log Injection 방지**: CRLF 이스케이프

### Sensitive Data Logging
- **Record toString() 오버라이드**: 마스킹 적용
- **LogicExecutor 마스킹**: TaskContext에 민감 정보 제외

## OWASP Top 10 Coverage

| 카테고리 | 가드레일 | 커버리지 |
|----------|---------|----------|
| A01: Broken Access Control | GR-SEC-003, GR-SEC-008 | ✅ |
| A02: Cryptographic Failures | GR-SEC-001, GR-SEC-005 | ✅ |
| A03: Injection | GR-SEC-003, GR-SEC-008 | ✅ |
| A04: Insecure Design | GR-SEC-008 | ✅ |
| A05: Security Misconfiguration | GR-SEC-002, GR-SEC-006 | ✅ |
| A06: Vulnerable Components | GR-SEC-009 | ✅ |
| A07: Auth Failures | GR-SEC-001, GR-SEC-008 | ✅ |
| A08: Data Integrity | GR-SEC-004 | ✅ |
| A09: Logging Failures | GR-SEC-004 | ✅ |
| A10: Server-Side SSRF | GR-SEC-007 | ✅ |

## 관련 문서

- [docs/03_Technical_Guides/security-hardening.md](../../03_Technical_Guides/security-hardening.md)
- [docs/03_Technical_Guides/security-testing.md](../../03_Technical_Guides/security-testing.md)
- [docs/03_Technical_Guides/security-checklist.md](../../03_Technical_Guides/security-checklist.md)
- [docs/03_Technical_Guides/security-incident-response.md](../../03_Technical_Guides/security-incident-response.md)
- [docs/03_Technical_Guides/infrastructure.md](../../03_Technical_Guides/infrastructure.md) (Section 18-19)

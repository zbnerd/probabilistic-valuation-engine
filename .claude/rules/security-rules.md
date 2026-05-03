---
paths:
  - "module-web/**/security/**/*.kt"
  - "module-web/**/security/**/*.java"
  - "module-web/**/config/**/*.kt"
  - "module-web/**/config/**/*.java"
  - "module-app/**/security/**/*.kt"
  - "module-app/**/config/**/*.kt"
---

# 보안 규칙 (Security Rules)

## Deny-by-Default

- SecurityConfig: `.anyRequest().denyAll()` 기본
- 새 endpoint는 명시적 permit configuration 필요
- **근거**: #624 (permitAll 대신 denyAll 필요)

## API Key 검증

- 외부 API key는 실제 외부 서비스에 대해 검증
- 임의 문자열 수용 금지
- **근거**: #667 (API key validation bypass)

## Auth Endpoint 보호

- 인증 endpoint에 rate limiting 필수
- Brute-force token generation 방지
- **근거**: #652 (auth endpoint rate limiting 누락)

## Identity 검증

- Identity는 검증된 외부 데이터(Nexon account_id 등)에 기반
- Hash-based fingerprint에 의존 금지
- **근거**: #662 (self-like prevention)

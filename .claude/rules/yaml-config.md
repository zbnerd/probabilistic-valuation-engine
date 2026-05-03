---
paths:
  - "**/*.yml"
  - "**/*.yaml"
---

# YAML Configuration Rules (AI 수정 시 필수 준수)

**금지 패턴:**
- 동일 root key 중복 생성 (예: `spring:` 블록이 파일 내 여러 개)
- 기존 블록 무시하고 파일 끝에 append
- 중첩된 키 실수 (예: `spring.spring.data`)

**필수 규칙:**
1. **수정 전 전체 파일 읽기**: 구조 파악 후 수정
2. **기존 블록에 merge**: 새 root key 생성 금지
3. **프로필별 설정 분리**: 환경 설정은 `application-{profile}.yml` 사용
4. **들여쓰기 검증**: YAML은 2-space, 중첩 레벨 정확히 확인

**프로필 구조:**
```
application.yml          # 공통 설정 (592줄)
├── application-local.yml  # 로컬 개발
├── application-prod.yml   # 프로덕션
├── application-ci.yml     # CI/CD
└── application-test.yml   # 테스트 (src/test/resources)
```

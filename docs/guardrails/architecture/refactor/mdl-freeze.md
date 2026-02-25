---
id: GR-REFACTOR-003
category: architecture/refactor
severity: warning
keywords: [mysql, ddl, mdl, metadata-lock, lock-wait-timeout]
languages: [java, yaml, sql]
---

# MDL (Metadata Lock) Freeze

## DON'T (위반 사항/장애 원인)

### 위험 설정
```yaml
# MySQL 기본 lock_wait_timeout (1년)
spring:
  datasource:
    hikari:
      connection-init-sql: ""  # 기본값 사용
```

### 위험 요소
- **DDL 실행 시 후속 쿼리 5건 이상 블로킹**
- **기본 lock_wait_timeout**: 1년 (31536000초)
- **MDL Cascade**: DDL이 실행 중인 동안 모든 쿼리가 대기

### 수치 (Before)
- Blocked queries: 5건 이상
- Wait timeout: 1년 (기본값)

## DO (수정 방법/재발 방지)

### 수정 설정
```yaml
# application.yml
spring:
  datasource:
    hikari:
      # 세션 타임아웃을 10초로 설정
      connection-init-sql: "SET SESSION lock_wait_timeout = 10"
```

### 수정 코드
```java
// HikariCP Config
hikariConfig.setConnectionInitSql("SET SESSION lock_wait_timeout = 10");
```

### 개선 수치 (After)
- Blocked queries: 제한된 시간 내에 해제
- Wait timeout: 1년 → 10초

### 핵심 원칙
1. **connection-init-sql 사용**: 모든 커넥션에 일관된 타임아웃 적용
2. **짧은 대기 시간**: DDL 블로킹 시 빠른 실패로 사용자 경험 보호
3. **Online DDL 도구**: 프로덕션에서는 pt-online-schema-change 또는 gh-ost 사용

## 출처
- 문서: [docs/05_Reports/04_05_Incidents/P0_Issues_Resolution_Report.md](../../../05_Reports/04_05_Incidents/P0_Issues_Resolution_Report.md)
- 이슈: #227 (N07-MDL Freeze)
- Nightmare: MetadataLockFreezeNightmareTest

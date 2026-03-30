# ADR-341: MySQL Dependency Removal

## Status

**ACCEPTED** (2026-03-11)

## Context

Issue #591에서 MySQL 의존성을 완전히 제거하고 PostgreSQL로 마이그레이션해야 합니다.

### Background

V5 마이그레이션의 일환으로 Redis(#589), MongoDB(#590)에 이어 마지막으로 MySQL을 PostgreSQL로 통합합니다.

### 기존 MySQL 구현

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/
├── lock/MySqlNamedLockStrategy.kt        # MySQL Named Lock
├── resilience/
│   ├── MySQLFallbackProperties.kt       # Fallback 설정
│   ├── MySQLHealthState.kt              # 상태 관리
│   └── event/
│       ├── MySQLDownEvent.kt            # 장애 이벤트
│       └── MySQLUpEvent.kt              # 복구 이벤트
└── module-common/src/main/kotlin/.../
    └── exception/MySQLFallbackException.kt
```

### PostgreSQL 대체 구현 (이미 존재)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/
├── lock/PostgresAdvisoryLockStrategy.kt  # PostgreSQL Advisory Lock
├── postgres/PostgresLockHikariConfig.kt  # 전용 Connection Pool
└── resilience/ (Redis 기반 Fallback)
```

## Decision

MySQL 의존성을 완전히 제거하고 PostgreSQL Advisory Lock으로 전환합니다.

### Migration Changes

1. **build.gradle**
   - `mysql-connector-j` 런타임 의존성 제거
   - `testcontainers.mysql` 테스트 의존성 제거

2. **docker-compose.yml**
   - MySQL 서비스 제거
   - MongoDB 서비스 제거 (Issue #590)
   - Redis 서비스 제거 (Issue #589)
   - PostgreSQL + PGMQ만 유지

3. **application.yml**
   - `driver-class-name`: `com.mysql.cj.jdbc.Driver` → `org.postgresql.Driver`
   - `lock.impl`: `redis` → `postgres`
   - `cache.l2.impl`: `redis` → `postgres`
   - MySQL Resilience 설정 비활성화

4. **application-local.yml / application-ci.yml**
   - MySQL datasource URL → PostgreSQL datasource URL
   - MySQL connection init SQL 제거

5. **삭제된 파일**
   - `MySqlNamedLockStrategy.kt`
   - `MySQLFallbackProperties.kt`
   - `MySQLHealthState.kt`
   - `MySQLDownEvent.kt`
   - `MySQLUpEvent.kt`
   - `MySQLFallbackException.kt`

6. **테스트 파일 수정**
   - `AbstractContainerBaseTest.java`: MySQLContainer → PostgreSQLContainer
   - `SharedContainers.java`: MYSQL → POSTGRES
   - `AppIntegrationTestSupport.java`: MySQL → PostgreSQL

### PostgreSQL Advisory Lock

```kotlin
@Component
class PostgresAdvisoryLockStrategy(
    private val jdbcTemplate: JdbcTemplate
) : DistributedLockStrategy {

    override fun tryLock(key: String, waitSeconds: Long): Boolean {
        val lockKey = key.hashCode().toLong()
        val result = jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)",
            Boolean::class.java,
            lockKey
        )
        return result == true
    }
}
```

### Configuration Changes

```yaml
# Before (MySQL)
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"

# After (PostgreSQL)
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    # No connection-init-sql needed
```

## Consequences

### Positive

- 단일 데이터베이스(PostgreSQL)로 완전한 통합
- 운영 복잡도 대폭 감소 (MySQL, MongoDB, Redis 제거)
- PostgreSQL Advisory Lock은 트랜잭션 스코프로 자동 해제
- PGMQ를 통한 메시지 큐 통합

### Negative

- MySQL Named Lock의 세션 스코프 기능 손실
- MySQL 전용 최적화 쿼리 재작성 필요

### Mitigation

- `pg_try_advisory_xact_lock` 사용으로 데드락 방지
- PostgreSQL EXPLAIN ANALYZE로 쿼리 최적화

## Related

- Issue #591: MySQL 의존성 완전 제거
- Issue #590: MongoDB 의존성 제거
- Issue #589: Redis 의존성 제거
- ADR-001: PostgreSQL Single DB Strategy
- ADR-003: PostgreSQL Advisory Lock
- ADR-022: Redis Dependency Removal
- ADR-023: MongoDB Dependency Removal

## History

- 2026-03-11: Initial proposal and acceptance

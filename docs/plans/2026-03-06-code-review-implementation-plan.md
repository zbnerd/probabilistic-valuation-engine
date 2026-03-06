# Probabilistic Valuation Engine 종합 코드 리뷰 구현 계획

> **Version**: v1.0
> **Date**: 2026-03-06
> **작성자**: Claude Opus 4.6 (glm-5.3-codex)

## 1. Research Summary

### 코드베이스 분석 결과

**이미 구현된 것 (좋음):**
- EventOutbox Pattern: 이미 구현됨 (`module-infra/src/main/kotlin/maple/expectation/domain/v2/EventOutbox.kt`)
- Hexagonal Architecture: module-core는 순수함 (JPA 의존성 없음)
- Rate Limiting: Bucket4j + Redis 기반 이미 구현됨 (`module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/`)
- LogicExecutor Pattern: try-catch 블록이 LogicExecutor로 대체됨
- Redis Streams Consumer: 이미 구현됨 (`MongoDBSyncWorker.java`)
- JwtTokenProvider: 이미 구현됨 (알고리즘 강제 포함)
- Secret Externalization: 환경 변수 사용 중
- Testcontainers: 이미 구현됨
- HikariCP Configuration: 이미 적절함
- Gradle Multi-Module: 잘 구조화됨
- FQCN 금지: import 스타일 준수됨

**발견된 문제 (해결 필요):**
- P0-1: Dual-Write 취약점: `MongoDBSyncWorker`에서 직접 Redis XADD 호출
- P0-2: Redis Streams Consumer 개선 필요: PEL 처리, DLQ, Poison Pill
- P0-3: JWT 알고리즘 검증 추가 필요
- P1-7: ArchUnit 테스트로 아키텍처 검증 자동화
- P1-8: Kotlin data class JPA 엔티티 검증 필요
- P1-9~16: 다양한 아키텍처 및 설정 검증 필요
- P2-17~26: 빌드, 테스트, 문서화 개선 필요

---

## 2. Work Units (26개)

### Unit 1: P0-1 Redis Streams Dual-Write 수정
**파일**:
- `module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java`
- `module-app/src/main/java/maple/expectation/application/service/expectation/event/MongoSyncEventPublisher.java`

**변경 내용**:
- MongoDBSyncWorker에서 직접 Redis XADD 호출 제 EventOutbox 저장으로 변경
- 트랜잭션션 내에서 안전하게 이벤트 저장 보장

---

### Unit 2: P0-2 Redis Streams Consumer PEL/DLQ 처리
**파일**:
- `module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java`

**변경 내용**:
- 2단계 시작 로직 구현 (PEL 먼저 처리)
- XPENDING times_delivered 카운터 확인
- DLQ 스트림으로 Poison Pill 이동
- XAUTOCLAIM 기반 좀비 메시지 복구

---

### Unit 3: P0-3 JWT 알고리즘 혼동 방지
**파일**:
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtTokenProvider.kt`

**변경 내용**:
- 알고리즘 화이트리스트 명시적 검증 추가
- `alg: none` 변형 차단

---

### Unit 4: P0-4~6 보안 검증 (이미 대부분 해결됨)
**상태**: 검증 완료로 확인 후 스킵 가능

---

### Unit 5: P1-7 Hexagonal Architecture ArchUnit 테스트
**파일**:
- `module-app/src/test/java/architecture/ModuleDependencyTest.java`

**변경 내용**:
- module-core JPA 의존성 검증 테스트 추가
- module-core Spring 어노테이션 검증 테스트 추가

---

### Unit 6: P1-8 Kotlin data class JPA 엔티티 검증
**파일**:
- `module-infra/src/main/kotlin/maple/expectation/domain/v2/EventOutbox.kt`
- 새 파일: `module-app/src/test/java/architecture/KotlinJpaEntityTest.java`

**변경 내용**:
- data class JPA 엔티티 존재 여부 검증 ArchUnit 테스트 추가

---

### Unit 7: P1-9 Spring Batch Race Condition 방지
**파일**:
- `module-app/src/main/java/maple/expectation/scheduler/`
- `module-app/src/main/java/maple/expectation/application/worker/`

**변경 내용**:
- Read Model 버전 필드 추가
- 조건부 업데이트 로직 구현

---

### Unit 8: P1-10 MongoDB Double Eventual Consistency
**파일**:
- `module-app/src/main/resources/application-*.yml`

**변경 내용**:
- Read Preference 설정 문서화
- Read Concern 설정 검토

---

### Unit 9: P1-11 TransactionManager 명시적 바인딩
**파일**:
- `module-app/src/main/java/maple/expectation/config/`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/`

**변경 내용**:
- 멀티 DataSource TransactionManager 설정 검증
- 필요시 명@Transactional("transactionManager")` 명시적 지정

---

### Unit 10: P1-12 N+1 Query 최적화
**파일**:
- `module-infra/src/main/java/maple/expectation/infrastructure/persistence/repository/`

**변경 내용**:
- JOIN FETCH 사용 검토
- EntityGraph 적용 검토
- default_batch_fetch_size 설정 검토

---

### Unit 11: P1-13 Cache Stampede 방지
**파일**:
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/`

**변경 내용**:
- TieredCache 구현 검증 (이미 구현됨)
- Mutex 락 검증

---

### Unit 12: P1-14 HikariCP Connection Pool 검증
**파일**:
- `module-app/src/main/resources/application-*.yml`

**변경 내용**:
- maximum-pool-size 설정 검증
- Tomcat thread pool과 비율 검토

---

### Unit 13: P1-15 Event Ordering 보장
**파일**:
- `module-infra/src/main/kotlin/maple/expectation/domain/v2/EventOutbox.kt`
- `module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java`

**변경 내용**:
- 버전 번호 필드 검증
- 순서 보장 로직 검토

---

### Unit 14: P1-16 CORS Configuration
**파일**:
- `module-web/src/main/kotlin/maple/expectation/web/config/`

**변경 내용**:
- Spring Security CORS 설정 검토
- allowedOrigins + allowCredentials 검증

---

### Unit 15: P2-17 Gradle Build Config 검증
**파일**:
- `*/build.gradle`, `*/build.gradle.kts`
- `settings.gradle`, `settings.gradle.kts`

**변경 내용**:
- api vs implementation 검증
- kotlin-jpa 플러그인 allOpen 설정
- 버전 카탈로그 사용 검토

---

### Unit 16: P2-18 Component Scanning 검증
**파일**:
- `module-app/src/main/java/maple/expectation/ExpectationApplication.java`

**변경 내용**:
- scanBasePackages 설정 검증
- @EntityScan 설정 검증

---

### Unit 17: P2-19 Spring Batch Restart
**파일**:
- `module-app/src/main/java/maple/expectation/scheduler/`

**변경 내용**:
- Job Parameter 유니크 설정
- 비정상 종료 복구 로직 검토

---

### Unit 18: P2-20 Event Schema Evolution
**파일**:
- `module-infra/src/main/kotlin/maple/expectation/domain/v2/EventOutbox.kt`
- `module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java`

**변경 내용**:
- 이벤트 버전 필드 검증
- Upcaster 패턴 구현 검토

---

### Unit 19: P2-21 Input Validation
**파일**:
- `module-web/src/main/kotlin/maple/expectation/web/controller/`
- `module-web/src/main/kotlin/maple/expectation/web/dto/`

**변경 내용**:
- @Valid 어노테이션 추가 검토
- @Validated 클래스 레벨 추가 검토

---

### Unit 20: P2-22 Rate Limiting
**상태**: 이미 구현됨 (Bucket4j + Redis)

---

### Unit 21: P2-23 Test Coverage 강화
**파일**:
- `module-app/src/test/java/maple/expectation/`
- `module-infra/src/test/java/maple/expectation/`

**변경 내용**:
- 이벤트 핸들러 테스트 추가
- 통합 테스트 검증
- Awaitility 사용 검토

---

### Unit 22: P2-24 Open-in-View 설정
**파일**:
- `module-app/src/main/resources/application-*.yml`

**변경 내용**:
- spring.jpa.open-in-view=false 설정 확인

---

### Unit 23: P2-25 Async TaskExecutor 설정
**파일**:
- `module-app/src/main/java/maple/expectation/config/`

**변경 내용**:
- ThreadPoolTaskExecutor Bean 설정 검토
- corePoolSize, maxPoolSize 설정 검증

---

### Unit 24: P2-26 Documentation Update
**파일**:
- `docs/01_ADR/`
- `README.md`

**변경 내용**:
- ADR 문서 업데이트
- README 아키텍처 설명 검증

---

## 3. E2E Test Recipe

### For API Changes:
```bash
# 1. Build project
./gradlew clean build -x test

# 2. Start application (local profile)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Test endpoints with curl
curl -X GET http://localhost:8080/api/v1/health
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"username":"test","password":"test"}'
```

### For Redis Streams:
```bash
# 1. Verify Redis connection
redis-cli ping

# 2. Check stream info
redis-cli xinfo stream character-sync

# 3. Verify consumer group
redis-cli xinfo groups character-sync
```

### Skip E2E for:
- Unit 5-6: ArchUnit 테스트 (컴파일 타임 검증)
- Unit 15-18: 빌드/설정 검증 (정적 분석)
- Unit 24: 문서화 업데이트

---

## 4. Worker Instructions Template

```
After you change is completed:
1. Run unit tests: ./gradlew test --tests "maple.expectation.*Test"
2. Run e2e test recipe from plan (if applicable)
3. Commit changes with clear message
4. Report PR URL or status
```

---

## 5. Implementation Priority

| Priority | Units | Estimated Effort |
|----------|-------|-----------------|
| P0 (Critical) | 1-4 | High - Security/Data integrity |
| P1 (High) | 5-16 | Medium - Architecture |
| P2 (Medium) | 17-26 | Low - Quality/Docs |

**Recommended Execution Order**:
1. P0-2 (Redis Streams Consumer) - Data integrity critical
2. P0-3 (JWT Security) - Security critical
3. P1-7 (ArchUnit Tests) - Architecture validation
4. Remaining P1 items
5. P2 items in order

# ADR-022: Redis/Redisson 의존성 완전 제거

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-11 |
| 결정자 | MapleExpectation Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #589 |
| 선행 ADR | ADR-003 PostgreSQL Redis 대체 전략 |

---

## 1. 배경 (Context)

### 현재 상황

ADR-003에서 Redis 기능을 PostgreSQL로 대체하는 전략을 수립했고, 다음 구현체들이 이미 완료됨:

| 완료된 항목 | 구현체 | 상태 |
|------------|--------|------|
| 분산 락 | `PostgresAdvisoryLockStrategy` | ✅ @Primary |
| 메시지 큐 | `PGMQ` (PostgreSQL Message Queue) | ✅ 구현 완료 |
| L2 캐시 | `TieredCacheManager` (Caffeine L1 + PostgreSQL UNLOGGED L2) | ✅ 구현 완료 |
| Pub/Sub | `PostgresNotifySubscriber` | ✅ 구현 완료 |
| Single Flight | `PostgresSingleFlightStrategy` | ✅ 구현 완료 |

### 남은 작업

Redis/Redisson 의존성과 관련 코드가 여전히 존재하여 다음 문제 발생:

1. **불필요한 의존성**: `redisson-spring-boot-starter`, `bucket4j-redisson` 등이 build.gradle에 존재
2. **코드 복잡성**: Redis 관련 코드가 PostgreSQL 구현체와 공존하여 혼란 발생
3. **@ConditionalOnBean 남용**: 임시 방편으로 사용된 조건부 빈 생성

---

## 2. 결정 (Decision)

**Redis/Redisson 의존성을 완전히 제거하고 PostgreSQL 구현체로 통일한다.**

### 제거 대상

#### 2.1 의존성 (build.gradle)

| 모듈 | 제거할 의존성 |
|------|--------------|
| module-app | `redisson-spring-boot-starter`, `bucket4j-redisson` |
| module-infra | `redisson-spring-boot-starter`, `bucket4j-redisson` |
| module-chaos-test | `redisson-spring-boot-starter`, `spring-boot-starter-data-redis` |

#### 2.2 제거할 코드 파일

| 카테고리 | 파일 | 비고 |
|----------|------|------|
| **Lock** | `RedisDistributedLockStrategy.kt` | PostgresAdvisoryLockStrategy로 대체 |
| | `ResilientLockStrategy.kt` | Redis → MySQL Fallback 불필요 |
| **Session** | `RedisSessionRepository.kt` | PostgreSQL 또는 JWT로 대체 |
| | `RedisSessionRepositoryImpl.kt` | |
| **RefreshToken** | `RedisRefreshTokenRepository.kt` | PostgreSQL 테이블로 대체 |
| | `RedisRefreshTokenRepositoryImpl.kt` | |
| **Config** | `RedissonConfig.kt` | 제거 |
| **Cache** | `RedissonOperationAdapter.kt` | 제거 |
| | `RedissonLikeAtomicOperations.kt` | 제거 |
| | `LuaScriptProvider.kt` | 제거 |
| **Buffer** | `RedisBufferStrategy.kt` | PGMQ로 대체 |
| | `RedisBufferRepositoryImpl.kt` | |
| | `RedisQueueRecoveryHandler.kt` | |
| | `RedisLuaScriptExecutor.kt` | |
| **Message** | `RedisMessageQueue.kt` | PGMQ로 대체 |
| | `RedisMessageTopic.kt` | PostgreSQL LISTEN/NOTIFY로 대체 |
| | `RedisStreamPublisher.kt` | `PgmqStreamPublisher` 사용 |
| | `RedisStreamEventConsumer.kt` | |
| **Cache Invalid** | `RedisCacheInvalidationPublisher.kt` | PostgreSQL NOTIFY로 대체 |
| | `RedisCacheInvalidationSubscriber.kt` | `PostgresNotifySubscriber` 사용 |
| **Like** | `RedisLikeBufferStorage.kt` | PostgreSQL 기반으로 대체 |
| | `RedisLikeRelationBuffer.kt` | |
| | `RedisLikeRelationBufferAdapter.kt` | |
| | `RedisLikeEventPublisher.kt` | PGMQ 기반으로 대체 |
| | `RedisLikeEventSubscriber.kt` | |
| | `ReliableRedisLikeEventPublisher.kt` | |
| | `ReliableRedisLikeEventSubscriber.kt` | |
| | `AtomicLikeToggleExecutor.kt` | PostgreSQL 기반으로 변경 |
| **Rate Limiter** | `TwoBucketRateLimiter.kt` | Caffeine 기반으로 변경 |
| | Bucket4j Redis 설정 | Caffeine만 사용 |
| **Domain Port** | `RedisOperationPort.kt` | 제거 (더 이상 필요 없음) |
| **Domain Repository** | `RedisSessionRepository.kt` | 제거 |
| | `RedisRefreshTokenRepository.kt` | 제거 |

---

## 3. 대안 (Alternatives)

### A. 점진적 제거 (선택됨)

**방법:** 각 카테고리별로 순차적으로 제거

**장점:**
- 각 단계마다 테스트 검증 가능
- 문제 발생 시 롤백 용이

**단점:**
- 시간 소요

**평가:** ✅ 안전한 접근

### B. 일괄 제거

**방법:** 모든 Redis 코드를 한 번에 제거

**장점:**
- 빠른 완료

**단점:**
- 디버깅 어려움
- 큰 변경 범위

**평가:** ❌ 위험도 높음

---

## 4. 마이그레이션 계획

### Phase 1: 의존성 제거

1. `build.gradle`에서 Redis 관련 의존성 제거
2. 컴파일 에러 발생 파일 식별

### Phase 2: Lock 관련 코드 제거

1. `RedisDistributedLockStrategy` 제거
2. `ResilientLockStrategy` 제거
3. `LockStrategyConfiguration`에서 Redis 참조 제거
4. `PostgresAdvisoryLockStrategy`가 유일한 LockStrategy로 동작

### Phase 3: Session/RefreshToken 마이그레이션

1. `refresh_tokens` PostgreSQL 테이블 생성 (없는 경우)
2. `PostgresRefreshTokenRepository` 구현
3. Session은 JWT stateless로 전환 또는 PostgreSQL 세션 저장소 사용

### Phase 4: Buffer/Queue 마이그레이션

1. `RedisBufferStrategy` 제거 (PGMQ 이미 구현됨)
2. `RedisQueueRecoveryHandler` 제거
3. Like 관련 Buffer를 PostgreSQL 기반으로 변경

### Phase 5: Message Queue/PubSub 마이그레이션

1. `RedisMessageTopic` → PostgreSQL LISTEN/NOTIFY
2. `RedisCacheInvalidationPublisher/Subscriber` → `PostgresNotifySubscriber`

### Phase 6: Rate Limiter 마이그레이션

1. `TwoBucketRateLimiter` 제거
2. Bucket4j Caffeine 전용 구성

### Phase 7: 정리

1. 미사용 import 제거
2. `@ConditionalOnBean(RedissonClient)` 제거
3. 테스트 실행 및 검증

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **운영 단순화** | Redis 프로세스 제거, 단일 DB 운영 |
| **비용 절감** | Redis 메모리 리소스 절약 |
| **코드 단순화** | 이중 구현 제거 |
| **의존성 감소** | 외부 시스템 장애 포인트 제거 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **일시적 코드 변경** | 단계적 마이그레이션 |
| **테스트 필요** | 각 Phase마다 검증 |

---

## 6. 검증 방법

### 컴파일 검증

```bash
./gradlew compileKotlin compileJava --continue
```

### 테스트 실행

```bash
./gradlew test
```

### 아키텍처 검증

- Redis import가 없는지 확인
- `@ConditionalOnBean(RedissonClient)` 제거 확인
- LockStrategy가 PostgresAdvisoryLockStrategy만 사용하는지 확인

---

## 7. 롤백 전략

### 롤백 조건

| 조건 | 조치 |
|------|------|
| 컴파일 실패 해결 불가 | Git revert |
| 주요 테스트 실패 | Git revert |
| 성능 저하 > 50% | Redis 복원 검토 |

### 롤백 절차

1. `git revert`로 변경사항 되돌리기
2. build.gradle에 Redis 의존성 복원
3. 애플리케이션 재시작

---

## 8. 참고 자료

- [ADR-003 Redis 기능 PostgreSQL 대체 전략](003-postgresql-redis-replacement.md)
- [ADR-005 PostgreSQL Advisory Lock](005-postgresql-advisory-lock.md)
- [ADR-006 PostgreSQL LISTEN/NOTIFY](006-postgresql-listen-notify.md)
- [GitHub Issue #589](https://github.com/.../issues/589)

---

## 9. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-11 | ADR 초안 작성 | Claude (Ralph) |

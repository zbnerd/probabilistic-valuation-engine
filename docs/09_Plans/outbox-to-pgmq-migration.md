# Outbox → PGMQ 통합 마이그레이션 계획

**작성일**: 2026-03-31
**상태**: Proposed
**관련 ADR**: ADR-010, ADR-016, ADR-046, ADR-316
**관련 이슈**: #80, #229, #283, #552, #553

---

## 1. 배경

### 현재 상태: Outbox 3개 + PGMQ 5개 큐가 병존

```
┌─ Outbox 기반 (기존) ──────────────────────────────┐
│                                                     │
│  Donation Outbox                                    │
│    OutboxScheduler (15s) → OutboxProcessor          │
│    → sendNotification()                             │
│                                                     │
│  Nexon API Outbox                                   │
│    NexonApiOutboxScheduler (10s)                    │
│    → NexonApiOutboxProcessor → NexonApiRetryClient  │
│                                                     │
│  Event Outbox                                       │
│    EventOutboxScheduler (10s)                       │
│    → EventOutboxProcessor → PgmqStreamPublisher     │
│                                                     │
└─────────────────────────────────────────────────────┘

┌─ PGMQ 기반 (신규, 이미 구현됨) ───────────────────┐
│                                                     │
│  calculation_queue                                  │
│    CalculationQueueProducer → CalculationWorker      │
│    NexonDataQueueProducer  → (같은 Worker)          │
│                                                     │
│  donation_queue                                     │
│    DonationQueueProducer → DonationWorker            │
│                                                     │
│  like_sync_queue                                    │
│    LikeSyncQueueProducer → LikeSyncWorker             │
│                                                     │
│  expectation_calc_high                              │
│    → ExpectationCalcWorker                          │
│                                                     │
│  expectation_calc_low                               │
│    → ExpectationCalcLowWorker                       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 문제점

1. **중복 구현**: Donation/Event/Nexon API가 Outbox와 PGMQ Producer 양쪽에 구현 존재
2. **아키텍처 불일치**: ADR-316에서 "Outbox 제거, PGMQ로 통일" 결정했으나 아직 혼재
3. **운영 복잡도**: Outbox 테이블 3개 + 폴링 스케줄러 3개 + DLQ 핸들러 3개 유지 비용
4. **트랜잭션 보장 불일치**: PGMQ Producer는 @Transactional 없음 → 호출부에 의존

---

## 2. 목표

**Outbox 패턴 3개를 완전히 제거하고 PGMQ만으로 통일**

핵심 원칙: 모든 메시지 발행을 비즈니스 @Transactional 안에서 수행

```sql
-- 목표 패턴 (Case 1: Same-Transaction)
BEGIN;
  INSERT INTO business_table ...;
  SELECT pgmq.send('queue_name', ...);
COMMIT;
-- 둘 다 성공하거나 둘 다 롤백 → Outbox 불필요
```

---

## 3. 아키텍처 분석

### 3.1 강결합 분석

**경계 분리 상태: 양호**

```
module-core:
  NexonApiOutboxProcessorPort  ← Scheduler가 아는 건 이것만
  NexonApiOutboxMetricsPort
  OutboxProcessorPort
  OutboxMetricsPort

module-infra (구현체 — 내부 강결합은 교체 시 통째로 갈아끼움):
  NexonApiOutboxProcessor ←→ NexonApiRetryClient ←→ NexonApiOutbox Entity
  OutboxProcessor ←→ DlqHandler ←→ DonationOutbox Entity
  EventOutboxProcessor ←→ PgmqStreamPublisher ←→ EventOutbox Entity

module-app / module-web:
  NexonApiOutbox 참조 없음 ← 경계 넘는 의존 없음
```

**핵심**: Port가 이미 추출되어 있어 새 구현체로 교체 가능

### 3.2 트랜잭션 관점

| 패턴 | 현재 적용 | 원자성 |
|------|----------|--------|
| **Case 1**: TX 안에서 pgmq.send() | EventOutboxProcessor만 | 보장 |
| **Case 2**: TX 밖에서 pgmq.send() | 나머지 Producer 전부 | 미보장 |

목표: 모든 Producer를 Case 1로 통일

---

## 4. Phase별 마이그레이션 계획

### Phase 1: Event Outbox 제거 (난이도: 낮음)

**이유**: 이미 PGMQ에 발행 중. Outbox 테이블만 거치는 브릿지.

#### 변경 내용

```
Before:
Service → @Transactional { save EventOutbox }
→ EventOutboxScheduler (10s) poll
→ EventOutboxProcessor → PgmqStreamPublisher.publish()

After:
Service → @Transactional { save BusinessData; pgmqClient.send("v5_event_queue", msg) }
```

#### 신규/수정 파일

| 파일 | 작업 | 내용 |
|------|------|------|
| `module-app/.../service/*Service.kt` | 수정 | @Transactional 안에 pgmqClient.send() 추가 |
| `module-infra/.../pgmq/PgmqClient.kt` | 수정 | (필요시) send 오버로드 |

#### 삭제 파일 (~10개)

| 파일 | 위치 |
|------|------|
| `EventOutbox` 엔티티 | `module-infra/.../domain/v2/` |
| `EventOutboxRepository` | `module-infra/.../persistence/repository/` |
| `EventOutboxProcessor` | `module-infra/.../event/outbox/` |
| `EventOutboxFetchFacade` | `module-infra/.../event/outbox/` |
| `EventOutboxScheduler` | `module-infra/.../scheduler/` |
| `EventOutboxMetrics` | `module-infra/.../metrics/` |
| `EventDlqHandler` | `module-infra/.../event/outbox/` (있는 경우) |
| `EventOutboxProperties` | `module-infra/.../config/` |
| `PgmqStreamPublisher` | `module-infra/.../messaging/` |
| DB 테이블 | `event_outbox` |

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
```

---

### Phase 2: Donation Outbox 제거 (난이도: 중간)

**이유**: PGMQ Worker(`DonationWorker`)가 이미 존재. Service만 TX 안에서 send 호출로 변경.

#### 변경 내용

```
Before:
Service → @Transactional { save Donation; save DonationOutbox }
→ OutboxScheduler (15s) poll
→ OutboxProcessor → sendNotification()

After:
Service → @Transactional { save Donation; pgmqClient.send("donation_queue", msg) }
→ DonationWorker.poll() → alertPublisher.sendInfo()
```

#### 신규/수정 파일

| 파일 | 작업 | 내용 |
|------|------|------|
| `module-app/.../service/*DonationService.kt` | 수정 | Outbox save → pgmqClient.send() |
| `DonationWorker` | 수정 | (필요시) 알림 로직 보강 |

#### 삭제 파일 (~10개)

| 파일 | 위치 |
|------|------|
| `DonationOutbox` 엔티티 | `module-infra/.../domain/v2/` |
| `DonationOutboxRepository` | `module-infra/.../persistence/repository/` |
| `OutboxProcessor` (Donation) | `module-infra/.../donation/outbox/` |
| `OutboxFetchFacade` | `module-infra/.../donation/outbox/` |
| `OutboxScheduler` | `module-infra/.../scheduler/` |
| `OutboxMetrics` | `module-infra/.../donation/outbox/` |
| `DlqHandler` (Donation) | `module-infra/.../donation/dlq/` |
| `DlqAdminService` | `module-infra/.../donation/dlq/` |
| `OutboxDrainOnShutdown` | `module-infra/.../lifecycle/` |
| DB 테이블 | `donation_outbox` |

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
```

---

### Phase 3: Nexon API Outbox 제거 (난이도: 높음)

**이유**: 외부 API 재시도 로직(Exponential Backoff, DLQ, 4xx/5xx 분기)이 Outbox 엔티티에 밀착.
하지만 Port가 이미 추출되어 있어 **새 구현체로 교체** 가능.

#### 핵심 전략: Port 구현체 교체

```
Before:
NexonApiOutboxScheduler → NexonApiOutboxProcessorPort
                              ↑ 구현
                         NexonApiOutboxProcessor (Outbox 테이블 폴링)
                           ├── NexonApiOutboxFetchFacade (JPA SKIP LOCKED)
                           ├── NexonApiRetryClient (Entity 파라미터)
                           ├── NexonApiDlqHandler
                           └── NexonApiOutboxMetrics

After:
NexonApiOutboxScheduler → NexonApiOutboxProcessorPort  ← 변경 없음
                              ↑ 새 구현
                         NexonApiPgmqProcessor (PGMQ 큐 소비)
                           ├── PgmqClient
                           ├── NexonApiClient (기존 재사용)
                           └── NexonApiPgmqMetrics
```

#### NexonApiOutbox Entity 내부 로직 이관

Entity에 박혀 있는 로직을 Worker로 이관:

| Entity 로직 | PGMQ 대체 |
|-------------|----------|
| `markFailed()` → `retryCount++` + `2^retryCount * 30s` backoff | `pgmq.set_visibility_timeout()` |
| `shouldMoveToDlq()` → maxRetries(10) 초과 | Worker에서 `retryCount >= 10` 체크 |
| `verifyIntegrity()` → SHA-256 content hash | 메시지 payload 검증 |
| `forceDeadLetter()` → DLQ 테이블 | `pgmq.delete()` + File backup + Discord alert |

#### PGMQ에서 Exponential Backoff 구현

```sql
-- 재시도 시 visibility timeout 증가
SELECT pgmq.set_visibility_timeout(
    'nexon_retry_queue',
    msg_id,
    interval '30 seconds' * pow(2, retry_count)
);
```

#### 신규 파일

| 파일 | 내용 |
|------|------|
| `NexonApiPgmqProcessor` | `NexonApiOutboxProcessorPort`의 PGMQ 구현체 |
| `NexonRetryMessage` | Entity 의존 없는 순수 DTO |
| `NexonApiPgmqMetrics` | `NexonApiOutboxMetricsPort`의 PGMQ 구현체 |

#### 수정 파일

| 파일 | 내용 |
|------|------|
| `docker/postgres/init.sql` | `SELECT pgmq.create('nexon_retry_queue')` 추가 |
| `application.yml` | `nexon.retry.backend=pgmq` feature flag |

#### 삭제 파일 (~12개)

| 파일 | 위치 |
|------|------|
| `NexonApiOutbox` 엔티티 | `module-infra/.../domain/v2/` |
| `NexonApiOutboxRepository` | `module-infra/.../persistence/repository/` |
| `NexonApiOutboxProcessor` | `module-infra/.../nexon/outbox/` |
| `NexonApiOutboxFetchFacade` | `module-infra/.../nexon/outbox/` |
| `NexonApiOutboxScheduler` | `module-infra/.../scheduler/` |
| `NexonApiRetryClient` | `module-infra/.../nexon/outbox/` |
| `NexonApiOutboxMetrics` | `module-infra/.../nexon/outbox/` |
| `NexonApiDlqHandler` | `module-infra/.../nexon/dlq/` |
| `NexonApiOutboxFetchFacade` | `module-infra/.../nexon/outbox/` |
| DB 테이블 | `nexon_api_outbox` |

#### 기존 구현체에 Feature Flag 추가

```kotlin
// 기존 Outbox 구현 — 비활성화 가능
@Service
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "outbox", matchIfMissing = false)
class NexonApiOutboxProcessor(...) : NexonApiOutboxProcessorPort { ... }

// 신규 PGMQ 구현
@Service
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "pgmq", matchIfMissing = true)
class NexonApiPgmqProcessor(...) : NexonApiOutboxProcessorPort { ... }
```

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
# N19 카오스 테스트 재실행 (PGMQ 기반)
```

---

### Phase 4: 정리 (Cleanup)

#### 삭제 — 공통 인프라

| 파일 | 내용 |
|------|------|
| `OutboxProperties` | 공통 설정 (더 이상 사용 안 함) |
| `EventOutboxProperties` | Event Outbox 설정 |
| `OutboxProcessorPort` | core port (PGMQ Worker로 대체) |
| `OutboxMetricsPort` | core port |
| `NexonApiOutboxProcessorPort` | core port (Scheduler 제거 시 같이) |
| `NexonApiOutboxMetricsPort` | core port |

#### 삭제 — DB 스키마

```sql
DROP TABLE IF EXISTS event_outbox;
DROP TABLE IF EXISTS donation_outbox;
DROP TABLE IF EXISTS nexon_api_outbox;
-- 관련 DLQ 테이블도 함께
```

#### 삭제 — init.sql 정리

`docker/postgres/init.sql`에서 불필요한 큐 제거:
- `v4_buffer_queue` (사용 여부 확인)
- `v5_event_queue` (직접 발행으로 대체)
- `donation_outbox_queue` (donation_queue로 통일)

---

## 5. 최종 아키텍처

```
┌─ 마이그레이션 후 ──────────────────────────────────┐
│                                                     │
│  Service Layer                                      │
│    @Transactional {                                 │
│      businessRepository.save(data)                  │
│      pgmqClient.send("queue", message)              │
│    }                                                │
│       ↓ (same transaction)                          │
│                                                     │
│  PGMQ Queues (PostgreSQL 내부)                      │
│    ├── calculation_queue      → CalculationWorker   │
│    ├── donation_queue         → DonationWorker       │
│    ├── like_sync_queue        → LikeSyncWorker        │
│    ├── expectation_calc_high  → ExpectationCalcWorker │
│    ├── expectation_calc_low   → ExpectationCalcLowWorker│
│    └── nexon_retry_queue      → (NexonApiPgmqProcessor)│
│                                                     │
│  결과:                                              │
│    ❌ Outbox 테이블 3개 삭제                        │
│    ❌ Outbox Scheduler 3개 삭제                     │
│    ❌ DLQ Handler 3개 삭제                          │
│    ❌ FetchFacade 3개 삭제                          │
│    ❌ RetryClient 삭제                              │
│    ✅ PGMQ만으로 원자성 + 재시도 + DLQ 보장        │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 6. 리스크 관리

| 리스크 | 영향 | 대응 |
|--------|------|------|
| 기존 Outbox PENDING 데이터 | 마이그레이션 시점 미처리 건 | 전환 전 Outbox 완전 비운 후 스위치 |
| N19 재현 불가 | PGMQ 기반 카오스 테스트 필요 | N19 시나리오 PGMQ 버전으로 재작성 |
| PGMQ visibility timeout 정밀도 | 기존 LocalDateTime vs PGMQ 초 단위 | 기능적 차이 없음 |
| 모니터링 갭 | Outbox Prometheus 메트릭 → PGMQ 메트릭 | PGMQ 큐 길이 쿼리로 대체 |
| 롤백 | PGMQ 전환 후 장애 | Feature Flag로 즉시 Outbox 복귀 가능 |

---

## 7. 실행 원칙

1. **각 Phase 독립 배포**: Phase 1 → 검증 → Phase 2 → 검증 → Phase 3
2. **Feature Flag**: 각 Phase마다 Outbox/PGMQ 전환 가능하게 구현
3. **운영 검증 후 삭제**: Feature Flag로 전환 후 1주일 관찰, 이상 없으면 Outbox 코드 삭제
4. **ADR 업데이트**: 완료 후 ADR-316 상태를 Accepted → Superseded (PGMQ-only)로 변경

---

## 8. 삭제 예상 총 파일 수

| Phase | 삭제 파일 | 신규/수정 파일 |
|-------|----------|---------------|
| Phase 1 (Event) | ~10개 | ~2개 수정 |
| Phase 2 (Donation) | ~10개 | ~2개 수정 |
| Phase 3 (Nexon API) | ~12개 | ~4개 신규, ~2개 수정 |
| Phase 4 (Cleanup) | ~6개 (공통) | ~1개 (init.sql) |
| **합계** | **~38개** | **~11개** |

# Outbox → PGMQ 통합 마이그레이션 계획

**작성일**: 2026-03-31
**상태**: Proposed (Consensus Review 반영)
**관련 ADR**: ADR-010, ADR-016, ADR-046, ADR-316
**관련 이슈**: #80, #229, #283, #552, #553
**리뷰**: 3-Agent Consensus Review (Architect + Critic + Code-Reviewer) 완료

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

### [P0] 트랜잭션 원자성 검증 필수

**주의**: PGMQ는 내부적으로 UNLOGGED 테이블을 사용하므로 `pgmq.send()`가 즉시 커밋될 가능성이 있음.
Phase 0에서 반드시 검증해야 함:

```sql
-- 검증 테스트: pgmq.send()가 호출자 TX에 참여하는지 확인
BEGIN;
  SELECT pgmq.send('test_queue', '{"test": true}'::jsonb);
  -- 여기서 ROLLBACK 후 큐에 메시지가 남아있는지 확인
  -- 남아있다면 UNLOGGED → 즉시 커밋 → Outbox 불필요 불가
ROLLBACK;
SELECT count(*) FROM pgmq.q_test_queue;
-- count = 0 이어야 함 (TX 참여 확인)
```

검증 결과에 따라:
- **TX 참여 확인** → 계획대로 진행
- **TX 미참여** → Outbox 제거 불가, 대안 설계 필요 (이 경우 ADR-316 재검토)

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

### 3.3 [P1] NexonApiEventType Enum 의존성

현재 `NexonApiEventType` enum이 `NexonApiOutbox` Entity 내부에 정의됨.
이관 시 반드시 core 또는 DTO 계층으로 추출 필요:

```
Before:
  NexonApiOutbox Entity {
    enum class NexonApiEventType { GET_OCID, GET_CHARACTER_BASIC, GET_ITEM_DATA, GET_CUBES }
  }

After:
  NexonApiEventType → module-core port 또는 NexonRetryMessage DTO로 이관
  Entity 삭제 시 enum도 함께 삭제 가능
```

---

## 4. Phase별 마이그레이션 계획

### Phase 0: 사전 준비 (난이도: 낮음, 모든 Phase 선행)

**이유**: Phase 1-3 실행 전 필수 인프라 준비. 누락 시 Phase 3에서 차단.

#### 0-1. [P0] PgmqClient.setVisibilityTimeout() 추가

현재 `PgmqClient`에는 `send/read/archive/delete/queueLength`만 존재.
Exponential Backoff 구현을 위해 `setVisibilityTimeout` 필수:

```kotlin
// PgmqClient.kt에 추가
fun setVisibilityTimeout(queueName: String, messageId: Long, timeoutSeconds: Long): Boolean {
    return jdbcTemplate.queryForObject(
        "SELECT pgmq.set_visibility_timeout(\$1, \$2, interval '\$3 seconds')",
        Boolean::class.java,
        queueName, messageId, timeoutSeconds
    ) ?: false
}
```

#### 0-2. [P0] TX 원자성 검증 테스트

```bash
# 통합 테스트 (Testcontainers 필요하나 CI에서만 실행)
# 1. BEGIN → pgmq.send() → ROLLBACK → 큐에 메시지 없어야 함
# 2. BEGIN → pgmq.send() → COMMIT → 큐에 메시지 있어야 함
```

#### 0-3. [P1] NexonApiEventType core 이관

`NexonApiEventType` enum을 `module-core`의 독립 파일로 추출.
기존 Entity에서는 import하여 사용 (Phase 3에서 Entity 삭제 시 의존성 정리).

#### 0-4. [P1] @Transactional 보장 전략 수립

PGMQ Producer가 반드시 활성 TX 내에서 호출되도록 보장:

```kotlin
// 옵션 A: AOP Guard (권장)
@Aspect
@Component
class TransactionGuardAspect {
    @Before("execution(* maple..*.pgmq.PgmqClient.send(..))")
    fun verifyTransactionActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IllegalStateException("pgmqClient.send() must be called within @Transactional")
        }
    }
}

// 옵션 B: PgmqClient.send() 내부에서 TransactionSynchronizationManager 체크
// 옵션 C: 호출부 주석 + 코드 리뷰 가드
```

#### 0-5. [P1] PgmqWorkerMetrics 인프라 추가

PGMQ 전용 Prometheus 메트릭 구조체 정의:

```kotlin
data class PgmqWorkerMetrics(
    val queueName: String,
    val messagesProcessed: Counter,
    val messagesFailed: Counter,
    val processingDuration: Timer,
    val queueLength: Gauge,  // pgmq.queue_length() 기반
)
```

각 Worker가 시작 시 메트릭 등록, `process()` 성공/실패 시 카운터 증가.

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test
# TX 원자성 검증 테스트 (CI 환경)
```

---

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

#### [P0] 컷오버 절차 (Event Outbox)

```
1. 기존 Outbox PENDING 건 완전 소진 대기
   SQL: SELECT count(*) FROM event_outbox WHERE status = 'PENDING';
   → count = 0 확인

2. Feature Flag 전환 (application.yml)
   event.outbox.enabled=false
   event.pgmq.direct=true

3. 배포 후 검증
   - PGMQ 큐에 메시지 적재 확인
   - Outbox 테이블 신규 INSERT 없는지 확인 (5분간 모니터링)

4. 1주일 관찰 후 Outbox 코드/테이블 삭제
```

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test

# 단위 테스트
./gradlew :module-infra:test --tests "*Event*"
./gradlew :module-app:test --tests "*Service*"

# [P2] 카오스 테스트: Event 발행 중 DB 장애 시나리오
# - TX 롤백 → 큐에 메시지 미존재 확인
# - 정상 복구 후 재시도 → 메시지 발행 확인
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
| `DonationWorker` | 수정 | 알림 로직 보강 + 무결성 검증 추가 |

#### [P1] DonationWorker 무결성 검증 추가

기존 OutboxProcessor의 Content Hash 검증이 마이그레이션 시 손실됨.
DonationWorker에 동등 검증 로직 추가:

```kotlin
// DonationWorker.process() 내
override fun process(message: PgmqMessage<DonationMessage>): Boolean {
    // [P1] 무결성 검증: Content Hash 확인
    val expectedHash = computeContentHash(message.payload)
    if (message.payload.contentHash != null && message.payload.contentHash != expectedHash) {
        log.error("[DonationWorker] Content hash mismatch: msgId={}", message.messageId)
        // → File backup 후 Discord alert, 메시지 삭제
        return false
    }
    // 기존 알림 처리 로직
    alertPublisher.sendInfo(message.payload)
    return true
}
```

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

#### [P0] 컷오버 절차 (Donation Outbox)

```
1. 기존 Outbox PENDING 건 완전 소진 대기
   SQL: SELECT count(*) FROM donation_outbox WHERE status = 'PENDING';
   → count = 0 확인

2. Feature Flag 전환
   donation.outbox.enabled=false
   donation.pgmq.direct=true

3. 배포 후 검증
   - DonationWorker가 donation_queue 소비 확인
   - Discord 알림 정상 발송 확인
   - Content Hash 검증 동작 확인

4. 1주일 관찰 후 Outbox 코드/테이블 삭제
```

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test

# 단위 테스트
./gradlew :module-infra:test --tests "*Donation*"
./gradlew :module-infra:test --tests "*Outbox*"

# [P2] 카오스 테스트: 기부 알림 발송 실패 시나리오
# - Discord API 장애 → 메시지 재시도 확인
# - Content Hash 불일치 → DLQ 이동 확인
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
                         NexonApiPgmqProcessor (독립 구현, PgmqWorker 상속하지 않음)
                           ├── PgmqClient (visibility timeout 활용)
                           ├── NexonApiClient (기존 재사용)
                           └── NexonApiPgmqMetrics
```

> **[P1] 주의**: `NexonApiPgmqProcessor`는 `PgmqWorker<T>`를 상속하지 않음.
> 기존 `PgmqWorker`는 `@Scheduled` 기반 폴링 + 제네릭 타입 바인딩을 가짐.
> `NexonApiPgmqProcessor`는 `NexonApiOutboxProcessorPort`를 구현하며,
> 내부적으로 `PgmqClient`를 직접 호출하는 독립 `@Component`로 구현.
> 재시도 로직(visibility timeout, backoff)은 Processor 내부에서 직접 관리.

#### NexonApiOutbox Entity 내부 로직 이관

Entity에 박혀 있는 로직을 Worker로 이관:

| Entity 로직 | PGMQ 대체 |
|-------------|----------|
| `markFailed()` → `retryCount++` + `2^retryCount * 30s` backoff | `pgmqClient.setVisibilityTimeout()` (Phase 0-1에서 추가) |
| `shouldMoveToDlq()` → maxRetries(10) 초과 | Worker에서 `retryCount >= 10` 체크 |
| `verifyIntegrity()` → SHA-256 content hash | `ContentHashUtil.verify()` (DTO 필드 + 유틸 클래스) |
| `forceDeadLetter()` → DLQ 테이블 | **File backup 먼저** → 그 다음 `pgmq.delete()` → Discord alert |

#### [P0] DLQ 처리 순서 수정

```
기존 계획 (위험): pgmq.delete() + File backup
수정된 계획 (안전): File backup → Discord alert → pgmq.delete()

이유: delete를 먼저 수행하면 backup 실패 시 메시지가 영구 손실됨.
      backup이 성공한 것을 확인한 후에만 delete 수행.
```

```kotlin
// NexonApiPgmqProcessor 내 DLQ 처리
fun moveToDlq(message: PgmqMessage<NexonRetryMessage>, reason: String) {
    // 1. File backup (반드시 먼저)
    val backupPath = dlqBackupService.backupToFile(message, reason)
    // 2. Discord alert
    discordAlertService.sendDlqAlert(message, reason, backupPath)
    // 3. PGMQ에서 삭제 (backup 성공 후)
    pgmqClient.delete(queueName, message.messageId)
}
```

#### [P0] Content Hash 이관

기존 `NexonApiOutbox.verifyIntegrity()`에서 SHA-256 Content Hash 검증.
Entity 없이도 검증 가능하도록 순수 유틸 + DTO 필드로 이관:

```kotlin
// module-infra/.../nexon/util/ContentHashUtil.kt (신규)
object ContentHashUtil {
    fun compute(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

// NexonRetryMessage DTO에 contentHash 필드 추가
data class NexonRetryMessage(
    val eventType: NexonApiEventType,  // Phase 0-3에서 core로 이관
    val payload: String,
    val retryCount: Int = 0,
    val contentHash: String,           // 발행 시 계산하여 포함
)
```

#### PGMQ에서 Exponential Backoff 구현

```sql
-- 재시도 시 visibility timeout 증가
SELECT pgmq.set_visibility_timeout(
    'nexon_retry_queue',
    msg_id,
    interval '30 seconds' * pow(2, retry_count)
);
```

```kotlin
// NexonApiPgmqProcessor 내부
fun handleRetry(message: PgmqMessage<NexonRetryMessage>, error: String) {
    val retryCount = message.payload.retryCount + 1
    if (retryCount >= MAX_RETRIES) {
        moveToDlq(message, "Max retries exceeded: $error")
        return
    }
    val backoffSeconds = min(2.0.pow(retryCount.toDouble()).toLong() * 30, 3600)
    pgmqClient.setVisibilityTimeout(queueName, message.messageId, backoffSeconds)
    metrics.recordRetry(message.payload.eventType, retryCount)
}
```

#### 신규 파일

| 파일 | 내용 |
|------|------|
| `NexonApiPgmqProcessor` | `NexonApiOutboxProcessorPort`의 PGMQ 구현체 (**PgmqWorker 비상속**) |
| `NexonRetryMessage` | Entity 의존 없는 순수 DTO (contentHash 포함) |
| `NexonApiPgmqMetrics` | `NexonApiOutboxMetricsPort`의 PGMQ 구현체 |
| `ContentHashUtil` | SHA-256 무결성 검증 유틸 |

#### 수정 파일

| 파일 | 내용 |
|------|------|
| `docker/postgres/init.sql` | `SELECT pgmq.create('nexon_retry_queue')` 추가 |
| `application.yml` | `nexon.retry.backend=pgmq` feature flag |
| `PgmqClient.kt` | `setVisibilityTimeout()` 추가 (Phase 0-1) |

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
| DB 테이블 | `nexon_api_outbox` |

#### 기존 구현체에 Feature Flag 추가

> **[P1] Bean 충돌 방지**: 두 구현체 모두 `@ConditionalOnProperty` 필수.
> `matchIfMissing`은 한쪽만 `true`로 설정하여 누락 시에도 단일 Bean 보장.

```kotlin
// 기존 Outbox 구현 — 비활성화 가능
@Service
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "outbox", matchIfMissing = false)
class NexonApiOutboxProcessor(...) : NexonApiOutboxProcessorPort { ... }

// 신규 PGMQ 구현 (기본값)
@Service
@ConditionalOnProperty(name = ["nexon.retry.backend"], havingValue = "pgmq", matchIfMissing = true)
class NexonApiPgmqProcessor(...) : NexonApiOutboxProcessorPort { ... }
```

#### [P0] 컷오버 절차 (Nexon API Outbox)

```
1. 기존 Outbox PENDING 건 완전 소진 대기
   SQL: SELECT count(*) FROM nexon_api_outbox WHERE status = 'PENDING';
   → count = 0 확인

   장기 PENDING(재시도 대기 중) 건도 확인:
   SQL: SELECT count(*) FROM nexon_api_outbox
        WHERE status = 'PENDING' AND next_retry_at < NOW() - INTERVAL '1 hour';
   → 오래된 건은 수동 처리 후 제거

2. nexon_retry_queue 생성 (init.sql 이미 배포됨)
   SQL: SELECT pgmq.create('nexon_retry_queue');

3. Feature Flag 전환
   nexon.retry.backend=pgmq  # application.yml

4. 배포 후 검증
   - PGMQ 큐에 메시지 적재 확인
   - Exponential Backoff 동작 확인 (visibility timeout 증가)
   - DLQ 처리 순서 확인 (backup → alert → delete)
   - Content Hash 검증 동작 확인

5. 롤백 시 즉시 복귀
   nexon.retry.backend=outbox  # 재배포 없이 설정만 변경

6. 1주일 관찰 후 Outbox 코드/테이블 삭제
```

#### 검증

```bash
./gradlew compileKotlin compileJava --continue
./gradlew test

# 단위 테스트
./gradlew :module-infra:test --tests "*NexonApi*"
./gradlew :module-infra:test --tests "*Outbox*"

# N19 카오스 테스트 재실행 (PGMQ 기반)
# - Nexon API 장애 → 재시도 → DLQ 전환 시나리오
# - PGMQ visibility timeout 정확도 검증
# - Content Hash 불일치 시나리오
```

---

### Phase 4: 정리 (Cleanup)

#### [P2] Port Deprecation (삭제 대신)

Hexagonal Architecture 원칙에 따라 Port 인터페이스는 즉시 삭제하지 않고
`@Deprecated` 마킹 후 일정 기간 유지:

```kotlin
@Deprecated("Outbox 제거 완료. PGMQ Worker로 대체됨. 다음 릴리즈에서 삭제 예정.")
interface OutboxProcessorPort { ... }

@Deprecated("Outbox 제거 완료. PGMQ Worker로 대체됨. 다음 릴리즈에서 삭제 예정.")
interface OutboxMetricsPort { ... }

@Deprecated("PGMQ 통합 완료. 다음 릴리즈에서 삭제 예정.")
interface NexonApiOutboxProcessorPort { ... }

@Deprecated("PGMQ 통합 완료. 다음 릴리즈에서 삭제 예정.")
interface NexonApiOutboxMetricsPort { ... }
```

삭제 시점: Phase 3 완료 후 **2주 뒤** (운영 검증 충분히 완료 후)

#### Deprecation — 공통 인프라

| 파일 | 내용 |
|------|------|
| `OutboxProperties` | 공통 설정 (더 이상 사용 안 함) |
| `EventOutboxProperties` | Event Outbox 설정 |
| `OutboxProcessorPort` | core port → `@Deprecated` |
| `OutboxMetricsPort` | core port → `@Deprecated` |
| `NexonApiOutboxProcessorPort` | core port → `@Deprecated` |
| `NexonApiOutboxMetricsPort` | core port → `@Deprecated` |

#### [P2] Port 반환값 비일관성 정리

현재 `NexonApiOutboxProcessorPort`의 `pollAndProcess()`가 `Unit` 반환.
PGMQ 구현체에서 처리 결과를 호출부에 전달할 수 없음.

해결: 반환값 변경 없이 유지 (Scheduler가 반환값을 사용하지 않음).
다만 향후 개선 시 `Result<Boolean>` 반환을 고려할 것.

#### 삭제 — DB 스키마

```sql
DROP TABLE IF EXISTS event_outbox;
DROP TABLE IF EXISTS donation_outbox;
DROP TABLE IF EXISTS nexon_api_outbox;
-- 관련 DLQ 테이블도 함께
```

#### 삭제 — init.sql 정리

`docker/postgres/init.sql`에서 불필요한 큐 제거:

```sql
-- 삭제 전 사용 여부 확인 필수
-- 1. v4_buffer_queue: codebase에서 "v4_buffer_queue" 검색 → 미사용 시 삭제
-- 2. v5_event_queue: Phase 1 완료 후 직접 발행으로 대체됨 → 삭제
-- 3. donation_outbox_queue: Phase 2 완료 후 donation_queue로 통일 → 삭제
```

#### [P2] PGMQ Archive 보관 정책

메시지 처리 후 `pgmq.archive()`된 메시지의 보관 기간 설정:

```sql
-- archive된 메시지는 pgmq.a_<queue_name> 테이블에 저장됨
-- 30일 후 자동 삭제 (pg_cron 또는 애플리케이션 스케줄러)
SELECT cron.schedule(
    'pgmq-archive-cleanup',
    '0 3 * * *',
    $$ SELECT pgmq.delete_all_archived('calculation_queue') $$
);
```

---

### Phase 5: [P1] LikeSyncWorker 평가 및 제거

**이유**: #664에서 DB Trigger(`fn_like_count_trigger`)가 `character_like` INSERT/DELETE 시
`like_count`를 자동 증감하므로 Worker가 stale 메시지만 처리 중.

현재 `LikeSyncWorker.process()`:
```kotlin
// LikeSyncWorker.kt:52-57
// #664: DB Trigger가 자동 증감하므로 app-level increment는 불필요
// 남은 PGMQ 메시지는 stale이며, V104 reconciliation이 이미 count를 보정함
log.info("[LikeSyncWorker] Acknowledged stale message (trigger handles count)...")
return true
```

#### 평가 항목

- [ ] `like_sync_queue`에 신규 메시지가 들어오는지 1주일 모니터링
- [ ] 들어온다면: Producer(`LikeSyncQueueProducer`) 호출부 확인 후 Producer도 함께 제거
- [ ] 들어오지 않는다면: Worker + Producer + 큐 정의 모두 제거

#### 삭제 후보 파일

| 파일 | 위치 |
|------|------|
| `LikeSyncWorker` | `module-infra/.../worker/` |
| `LikeSyncQueueProducer` | `module-infra/.../queue/pgmq/` |
| `LikeSyncRequest` | `module-infra/.../pgmq/` |
| `like_sync_queue` 큐 정의 | `docker/postgres/init.sql` |

---

## 5. 최종 아키텍처

```
┌─ 마이그레이션 후 ──────────────────────────────────┐
│                                                     │
│  Service Layer                                      │
│    @Transactional {                                 │
│      businessRepository.save(data)                  │
│      pgmqClient.send("queue", message)              │
│    }  ← TransactionGuardAspect로 TX 보장            │
│       ↓ (same transaction)                          │
│                                                     │
│  PGMQ Queues (PostgreSQL 내부)                      │
│    ├── calculation_queue      → CalculationWorker   │
│    ├── donation_queue         → DonationWorker       │
│    │                          (Content Hash 검증)    │
│    ├── expectation_calc_high  → ExpectationCalcWorker │
│    ├── expectation_calc_low   → ExpectationCalcLowWorker│
│    └── nexon_retry_queue      → NexonApiPgmqProcessor  │
│                               (독립 @Component)      │
│                               (visibility backoff)   │
│                                                     │
│  모니터링:                                          │
│    PgmqWorkerMetrics per Worker                     │
│    + PGMQ queue_length Gauge                        │
│    + Prometheus 카운터 (processed/failed)            │
│                                                     │
│  결과:                                              │
│    ❌ Outbox 테이블 3개 삭제                        │
│    ❌ Outbox Scheduler 3개 삭제                     │
│    ❌ DLQ Handler 3개 삭제                          │
│    ❌ FetchFacade 3개 삭제                          │
│    ❌ RetryClient 삭제                              │
│    ❌ (선택) LikeSyncWorker + Producer 삭제          │
│    ✅ PGMQ만으로 원자성 + 재시도 + DLQ 보장        │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 6. 리스크 관리

| 리스크 | 영향 | 대응 |
|--------|------|------|
| [P0] pgmq.send() TX 미참여 | Same-TX 전제 무너짐 | Phase 0에서 검증. 실패 시 ADR-316 재검토 |
| 기존 Outbox PENDING 데이터 | 마이그레이션 시점 미처리 건 | 전환 전 Outbox 완전 비운 후 스위치 (컷오버 절차) |
| [P0] 컷오버 중 듀얼 라이트 | 양쪽에 메시지 중복 발행 | Feature Flag로 원자적 전환, PENDING=0 확인 후 스위치 |
| N19 재현 불가 | PGMQ 기반 카오스 테스트 필요 | N19 시나리오 PGMQ 버전으로 재작성 |
| PGMQ visibility timeout 정밀도 | 기존 LocalDateTime vs PGMQ 초 단위 | 기능적 차이 없음 |
| [P1] 모니터링 갭 | Outbox Prometheus 메트릭 손실 | Phase 0-5에서 PgmqWorkerMetrics로 사전 대체 |
| 롤백 | PGMQ 전환 후 장애 | Feature Flag로 즉시 Outbox 복귀 가능 |
| [P1] Bean 충돌 | 두 구현체 동시 로드 | 양쪽 모두 @ConditionalOnProperty, 한쪽만 matchIfMissing=true |
| [P0] DLQ 순서 | backup 전 delete 시 메시지 영구 손실 | backup → alert → delete 순서 강제 |

---

## 7. 실행 원칙

1. **Phase 0 선행**: 모든 마이그레이션 전 필수 인프라 준비 완료
2. **각 Phase 독립 배포**: Phase 0 → Phase 1 → 검증 → Phase 2 → 검증 → Phase 3 → Phase 4 → Phase 5
3. **Feature Flag**: 각 Phase마다 Outbox/PGMQ 전환 가능하게 구현
4. **컷오버 절차 준수**: PENDING=0 확인 → Flag 전환 → 모니터링 → 코드 삭제
5. **운영 검증 후 삭제**: Feature Flag로 전환 후 1주일 관찰, 이상 없으면 Outbox 코드 삭제
6. **ADR 업데이트**: 완료 후 ADR-316 상태를 Accepted → Superseded (PGMQ-only)로 변경

### Phase별 테스트 전략

| Phase | 단위 테스트 | 카오스 테스트 | 모니터링 | 롤백 검증 |
|-------|-----------|-------------|---------|-----------|
| Phase 0 | TX 원자성, setVisibilityTimeout | - | - | - |
| Phase 1 | Event 발행/소비, TX 롤백 | DB 장애 시나리오 | 큐 길이 | Flag 복귀 |
| Phase 2 | Donation 알림, Content Hash | Discord API 장애 | 알림 성공률 | Flag 복귀 |
| Phase 3 | Backoff, DLQ 순서, 무결성 | Nexon API 장애, N19 재현 | 재시도율, DLQ율 | Flag 복귀 |
| Phase 4 | Deprecation 컴파일 | - | - | - |
| Phase 5 | (모니터링만) | - | 큐 메시지 유무 | - |

---

## 8. 삭제 예상 총 파일 수

| Phase | 삭제 파일 | 신규/수정 파일 |
|-------|----------|---------------|
| Phase 0 (Prerequisites) | 0개 | ~4개 신규 (setVisibilityTimeout, TX test, EventType 이관, Metrics) |
| Phase 1 (Event) | ~10개 | ~2개 수정 |
| Phase 2 (Donation) | ~10개 | ~2개 수정 |
| Phase 3 (Nexon API) | ~12개 | ~4개 신규 (Processor, DTO, Metrics, ContentHash), ~3개 수정 |
| Phase 4 (Cleanup) | ~6개 (공통 deprecated) | ~1개 (init.sql) |
| Phase 5 (LikeSync) | ~4개 | 0개 |
| **합계** | **~42개** | **~15개** |

---

## 9. Consensus Review 반영 이력

| 날짜 | 심각도 | 이슈 | 반영 위치 |
|------|--------|------|-----------|
| 2026-03-31 | P0 | PgmqClient.setVisibilityTimeout() 미존재 | Phase 0-1 추가 |
| 2026-03-31 | P0 | pgmq.send() TX 원자성 미검증 | Section 2 + Phase 0-2 추가 |
| 2026-03-31 | P0 | DLQ 처리 순서 (delete 먼저 위험) | Phase 3 DLQ 처리 순서 수정 |
| 2026-03-31 | P0 | 컷오버 중 듀얼 라이트 | Phase 1/2/3 컷오버 절차 추가 |
| 2026-03-31 | P0 | Content Hash 검증 손실 | Phase 3 ContentHashUtil 추가 |
| 2026-03-31 | P1 | PgmqWorkerMetrics 미정의 | Phase 0-5 추가 |
| 2026-03-31 | P1 | NexonApiEventType Entity 종속 | Phase 0-3 + Section 3.3 추가 |
| 2026-03-31 | P1 | PgmqWorker 상속 충돌 | Phase 3 비상속 명시 |
| 2026-03-31 | P1 | @Transactional 보장 없음 | Phase 0-4 추가 |
| 2026-03-31 | P1 | DonationWorker 무결성 검증 누락 | Phase 2 검증 로직 추가 |
| 2026-03-31 | P1 | Feature Flag Bean 충돌 | Phase 3 조건 명시 |
| 2026-03-31 | P1 | LikeSyncWorker stale | Phase 5 추가 |
| 2026-03-31 | P2 | 테스트 전략 확장 | Section 7 테이블 추가 |
| 2026-03-31 | P2 | init.sql 정리 검증 | Phase 4 검증 SQL 추가 |
| 2026-03-31 | P2 | Port 반환값 비일관성 | Phase 4 주석 추가 |
| 2026-03-31 | P2 | PGMQ Archive 보관 정책 | Phase 4 보관 정책 추가 |
| 2026-03-31 | P2 | Port 즉시 삭제 위험 | Phase 4 Deprecation으로 변경 |

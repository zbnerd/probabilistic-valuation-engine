---
id: GR-REFACTOR-004
category: architecture/refactor
severity: critical
keywords: [outbox, transactional, reliability, idempotency]
languages: [java, kotlin]
---

# Transactional Outbox Pattern - 이벤트 유실 방지

## DON'T (외부 API 직접 호출)
- @Transactional 메서드 내에서 외부 API 직접 호출
- API 실패 시 데이터 무결성 위반
- 재시도 메커니즘 없음

```java
// Bad: 트랜잭션 내에서 외부 API 호출
@Transactional
public Donation processDonation(DonationRequest request) {
    // 1. DB 저장 (성공)
    Donation donation = donationRepository.save(request.toEntity());

    // 2. 외부 API 호출 (실패 시 복구 불가)
    try {
        notifyDiscord(donation);  // 실패 시 알림 유실
    } catch (Exception e) {
        log.error("Discord notification failed", e);
        // ❌ 이미 커밋됨, 재시도 없음
    }
    return donation;
}
```

```kotlin
// Bad: 트랜잭션 내에서 실패 가능한 외부 호출
@Transactional
fun processDonation(request: DonationRequest): Donation {
    val donation = donationRepository.save(request.toEntity())

    try {
        notifyDiscord(donation)  // 실패 시 유실
    } catch (e: Exception) {
        log.error("Discord notification failed", e)
        // ❌ 복구 불가
    }
    return donation
}
```

## DO (Transactional Outbox Pattern)
- **동일 트랜잭션**으로 Outbox 저장
- 30초 폴링으로 자동 재시도
- SKIP LOCKED로 분산 환경 안전성 확보
- Exponential Backoff 재시도 전략

```java
// Good: Outbox Pattern으로 영속성 보장
@Transactional
public Donation processDonation(DonationRequest request) {
    // 1. DB 저장 (동일 트랜잭션)
    Donation donation = donationRepository.save(request.toEntity());

    // 2. Outbox 저장 (동일 트랜잭션)
    DonationOutbox outbox = DonationOutbox.builder()
        .eventType("DISCORD_NOTIFICATION")
        .payload(toJson(donation))
        .status(OutboxStatus.PENDING)
        .nextRetryAt(LocalDateTime.now())
        .build();
    donationOutboxRepository.save(outbox);

    return donation;  // 커밋 시 원자성 보장
}

// Scheduler: 30초마다 자동 재시도
@Scheduled(fixedRate = 30000)
public void processOutbox() {
    List<DonationOutbox> pending = outboxRepository.findPendingWithLock(
        List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
        LocalDateTime.now(),
        PageRequest.of(0, 100)
    );

    for (DonationOutbox entry : pending) {
        retryClient.processOutboxEntry(entry);
    }
}
```

```kotlin
// Good: Outbox Pattern
@Transactional
fun processDonation(request: DonationRequest): Donation {
    val donation = donationRepository.save(request.toEntity())

    val outbox = DonationOutbox.builder()
        .eventType("DISCORD_NOTIFICATION")
        .payload(toJson(donation))
        .status(OutboxStatus.PENDING)
        .nextRetryAt(LocalDateTime.now())
        .build()
    donationOutboxRepository.save(outbox)

    return donation
}

@Scheduled(fixedRate = 30000)
fun processOutbox() {
    val pending = outboxRepository.findPendingWithLock(
        listOf(OutboxStatus.PENDING, OutboxStatus.FAILED),
        LocalDateTime.now(),
        PageRequest.of(0, 100)
    )

    pending.forEach { entry ->
        retryClient.processOutboxEntry(entry)
    }
}
```

## SKIP LOCKED 쿼리 (분산 안전성)

```sql
-- MySQL 8.0+ SKIP LOCKED 패턴
SELECT * FROM donation_outbox
WHERE status IN ('PENDING', 'FAILED')
  AND next_retry_at <= NOW()
ORDER BY id
FOR UPDATE SKIP LOCKED  -- 이미 잠긴 행은 스킵
LIMIT 100;
```

**작동 원리:**
- Instance A: Row 1-100 획득
- Instance B: Row 101-200 획득 (1-100 스킵)
- 결과: **중복 처리 없음**

## Exponential Backoff 재시도 전략

| 재시도 횟수 | 대기 시간 | 누적 대기 시간 |
|:----------:|:--------:|:-------------:|
| 1차 | 30초 | 30초 |
| 2차 | 60초 | 1.5분 |
| 3차 | 120초 | 3.5분 |
| 4차 | 240초 | 7.5분 |
| 5차 | 480초 | 15.5분 |
| 6차+ | 최대 16분 | ~2시간 |

**최대 재시도:** 10회
**DLQ 전환:** 10회 실패 후 수동 개입

## Reconciliation 불변식 (Invariant)

```
expected_events = processed_success + dlq_events + ignored_duplicates
```

- **expected_events**: 장애 윈도우 동안 Outbox에 영구 저장된 총 이벤트 수
- **processed_success**: 대상 시스템에 성공 적용된 이벤트 수
- **dlq_events**: 재시도 불가능한 오류로 격리된 이벤트 수
- **ignored_duplicates**: 멱등성 탐지로 안전하게 스킵된 중복 이벤트 수

## 출처
- [N19 Outbox Replay Recovery Report](../../../../05_Reports/04_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)
- [ADR-016: Nexon API Outbox Pattern](../../../../adr/ADR-016-nexon-api-outbox-pattern.md)

## 복구 성과 (N19 사례)

| 메트릭 | 값 |
|--------|-----|
| Outbox 항목 수 | 2,160,000건 |
| 재처리 처리량 | 1,200 TPS (Peak) |
| 자동 복구율 | 99.98% |
| 복구 시간 | 47분 |
| 데이터 유실 | **0건** |

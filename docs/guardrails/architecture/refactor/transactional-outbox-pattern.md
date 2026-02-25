---
id: GR-REFACTOR-015
category: architecture/refactor
severity: info
keywords: [outbox, transactional, replay, resilience, skip-locked, idempotent]
languages: [java, kotlin, sql]
---

# Transactional Outbox Pattern (성공 사례)

## DON'T (기존 문제)

### 기존 방식의 위험 요소
- **외부 API 실패 시 이벤트 유실**: 재시도 메커니즘 부재
- **수동 복구 필요**: 운영자 개입으로 MTTD/MTTR 악화
- **분산 환경 중복 처리**: 멱등성 보장 없음

### 수치 (개선 전)
- 데이터 유실 가능성: HIGH
- 수동 복구 시간: 2시간 이상

## DO (성공 사례/재발 방지)

### 구현 코드
```java
// 1. Outbox 적재 (동일 트랜잭션)
@Transactional
public void processApiRequest(String ocid, RequestData data) {
    // DB 작업과 Outbox 적재를 동일 트랜잭션으로 실행
    repository.save(entity);
    outboxRepository.save(new NexonApiOutbox(ocid, data)); // 원자성 보장
}

// 2. 자동 재처리 스케줄러
@Scheduled(fixedRate = 30000) // 30초마다 폴링
public void pollAndProcess() {
    // SKIP LOCKED로 분산 환경 안전성 확보
    List<NexonApiOutbox> pending = outboxRepository.findPendingWithLock(
        List.of(PENDING, FAILED),
        LocalDateTime.now(),
        PageRequest.of(0, 100)
    );

    for (NexonApiOutbox entry : pending) {
        retryClient.processOutboxEntry(entry); // API 재시도
        if (success) {
            outboxRepository.delete(entry);     // 성공 시 삭제
        } else {
            entry.markFailed(error);            // 실패 시 재시도 스케줄
        }
    }
}
```

### SKIP LOCKED 쿼리
```sql
-- 분산 환경 중복 처리 방지
SELECT * FROM nexon_api_outbox
WHERE status IN ('PENDING', 'FAILED')
  AND next_retry_at <= NOW()
ORDER BY id
FOR UPDATE SKIP LOCKED  -- 이미 잠긴 행은 스킵
LIMIT 100;
```

### 수치 (개선 후 - N19 장애 복구)
- 이벤트 처리: 2,160,000건 (6시간 장애)
- 자동 복구율: 99.98% (47분 소요)
- 데이터 유실: 0건
- DLQ 전환: 52건 (0.002%)

### 핵심 원칙
1. **동일 트랜잭션**: Outbox 적재와 DB 작업을 원자적으로 실행
2. **자동 재처리**: Scheduler로 주기적 재시도 (Exponential Backoff)
3. **SKIP LOCKED**: 분산 환경에서 중복 처리 방지
4. **Triple Safety Net**: DLQ → File Backup → Discord Alert

## 출처
- 문서: [docs/05_Reports/04_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md](../../../05_Reports/04_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)
- 인시던트: N19-20260205-140000
- ADR: [ADR-016](../../../adr/ADR-016-nexon-api-outbox-pattern.md)

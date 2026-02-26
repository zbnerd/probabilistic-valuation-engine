---
id: GR-CHAOS-N19
category: testing/chaos
severity: critical
keywords: [Nightmare, chaos, N19, Outbox Replay, Transactional Outbox, External API Outage, Replay Throughput]
languages: [java, kotlin]
---

# [N19] Outbox Replay Flood

## DON'T (장애 원인)

외부 API 6시간 장애 시 **Outbox 테이블에 100만 건이 적재**된 후, **Replay 처리량 부족** 또는 **메시지 유실**이 발생합니다.

### 위험 코드 패턴

```java
// 위험: 충분한 처리량 없는 단일 스레드 Replay
@Scheduled(fixedDelay = 1000)
public void replayOutbox() {
    List<Outbox> pending = outboxRepository.findPending(10);  // 배치 너무 작음
    for (Outbox outbox : pending) {
        externalApi.send(outbox.getPayload());  // 동기 호출 느림
        outbox.markProcessed();
    }
}
```

### 장애 시나리오

```
1. 외부 API 6시간 장애 시작 (모든 요청 500 에러)
2. 비즈니스 트랜잭션 정상 처리 → Outbox에 100만 건 적재
3. Replay 스케줄러 주기적 시도 실패 (retries_exhausted 증가)
4. 6시간 후 외부 API 복구
5. Replay 스케줄러 대량 처리 시작... 하지만 처리량 부족 (10건/초)
6. 100만건 / 10건/초 = 100,000초 = 27.7시간 소요! ❌
```

### 장애 수치
- **Message Loss Risk**: 높음 (트랜잭션 보장 없을 시)
- **Replay Throughput**: 100 tps (부족)
- **Recovery Time**: 27.7시간 (1,000,000건 / 10tps)
- **Data Integrity**: < 99.99% (누락 가능)

---

## DO (재발 방지)

### 1. Transactional Outbox Pattern (메시지 유실 방지)

```java
@Transactional
public void processDonation(Long amount) {
    // 비즈니스 트랜잭션과 Outbox 적재를 원자적으로 실행
    donationRepository.save(new Donation(amount));

    // 같은 트랜잭션으로 Outbox 적재
    outboxRepository.save(NexonApiOutbox.create(
        "donation.created",
        createPayload(amount)
    ));
}  // COMMIT 시 두 데이터 모두 저장됨 ✅
```

### 2. Shard 기반 병렬 Replay (처리량 향상)

```java
@Scheduled(fixedDelay = 1000)
public void replayOutboxParallel() {
    int shardCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(shardCount);

    for (int shard = 0; shard < shardCount; shard++) {
        final int shardId = shard;
        executor.submit(() -> {
            List<Outbox> pending = outboxRepository.findPendingByShard(
                shardId, shardCount, batchSize  // 1000건 배치
            );
            replayBatch(pending);
        });
    }
}

// Repository: Shard 기반 분할
@Query("SELECT o FROM Outbox o WHERE MOD(o.id, :shardCount) = :shardId AND o.processed = false")
List<Outbox> findPendingByShard(@Param("shardId") int shardId,
                                @Param("shardCount") int shardCount,
                                Pageable pageable);
```

### 3. Reconciliation (정합성 검증)

```java
@Scheduled(cron = "0 0 2 * * ?")  // 매일 새벽 2시
public void reconcileOutbox() {
    LocalDate yesterday = LocalDate.now().minusDays(1);

    // Outbox 데이터와 External API 상태 비교
    List<Outbox> outboxEntries = outboxRepository.findByCreatedAt(yesterday);
    List<ExternalApiStatus> apiStatus = externalApi.checkStatus(yesterday);

    // 누락된 메시지 재전송
    outboxEntries.stream()
        .filter(outbox -> !apiStatus.contains(outbox.getRequestId()))
        .forEach(outbox -> {
            log.warn("♻️ [Reconciliation] 누락된 메시지 재전송: {}", outbox.getRequestId());
            replayService.replay(outbox);
        });
}
```

### 4. DLQ로 치명적 오류 격리

```java
private void replayBatch(List<Outbox> batch) {
    for (Outbox outbox : batch) {
        try {
            externalApi.send(outbox.getPayload());
            outbox.markProcessed();
        } catch (Exception e) {
            outbox.incrementRetries();
            if (outbox.getRetries() >= MAX_RETRIES) {
                dlqHandler.sendToDlq(outbox, e);  // Dead Letter Queue로 이동
            }
        }
    }
}
```

### 5. Prometheus 모니터링

```promql
# Outbox 적재량
outbox_pending_count

# Replay 처리량
rate(outbox_replayed_total[1m])

# DLQ 전송률
rate(outbox_dlq_total[5m])

# 알람 설정
- alert: OutboxBacklog
  expr: outbox_pending_count > 100000
  for: 5m
  labels:
    severity: critical
```

### 개선 수치 (테스트/설계 기준)
- **Message Loss**: 0건 (트랜잭션 보장)
- **Replay Throughput**: 1,000+ tps (Shard 기반 병렬)
- **Recovery Time**: < 20분 (1,000,000건 / 1,000tps / 10 Shard)
- **Data Integrity**: ≥ 99.99% (Reconciliation 검증)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N19-outbox-replay.md`
- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N19-compound-failures.md` (Compound Failures)
- `docs/05_Reports/04_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`

---
id: GR-CHAOS-N13
category: testing/chaos
severity: high
keywords: [Nightmare, chaos, N13, Zombie Outbox, Outbox Pattern, JVM Crash, Stale Recovery]
languages: [java, kotlin]
---

# [N13] Zombie Outbox

## DON'T (장애 원인)

JVM 크래시 시 **Outbox 항목이 PROCESSING 상태에서 영구 고착**되어 메시지가 처리되지도 재시도되지도 않습니다.

### 위험 코드 패턴

```java
// 위험: Stale Recovery 메커니즘 없음
@Scheduled(fixedDelay = 1000)
public void processOutbox() {
    List<Outbox> pending = outboxRepository.findPending(100);
    for (Outbox outbox : pending) {
        outbox.setStatus(OutboxStatus.PROCESSING);  // JVM 크래시 시 영구 보존
        outboxRepository.save(outbox);

        try {
            externalApi.send(outbox.getPayload());
            outbox.setStatus(OutboxStatus.COMPLETED);
        } catch (Exception e) {
            // 재시도 로직만 있고, Stale 복구 없음 ❌
            outbox.incrementRetries();
        }
        outboxRepository.save(outbox);
    }
}
```

### 장애 시나리오

```
1. Outbox 항목 처리 시작 → status: PROCESSING
2. JVM 크래시 (OOM, kill -9, 하드웨어 장애)
3. 항목이 PROCESSING 상태로 영구 고착
4. 재처리되지 않아 메시지 손실
```

### 장애 수치
- **Zombie Recovery Rate**: 0% (Stale Recovery 없을 시)
- **Permanent PROCESSING Entries**: 증가 (JVM restart 후에도 유지)
- **Message Loss Rate**: 100% (고착된 항목)

---

## DO (재발 방지)

### 1. recoverStalled() 스케줄러 추가

```java
@Scheduled(fixedDelay = 60000)  // 1분마다 실행
public void recoverStalled() {
    LocalDateTime staleTime = LocalDateTime.now().minusMinutes(STALE_THRESHOLD);
    int recovered = outboxRepository.resetStalledProcessing(staleTime);

    if (recovered > 0) {
        log.warn("♻️ [Outbox] Stalled 상태 복구: {}건", recovered);
    }
}
```

### 2. Repository에 Stale 항목 조회 메서드 추가

```java
@Modifying
@Query("UPDATE Outbox o SET o.status = 'PENDING', o.processedBy = NULL, o.processedAt = NULL " +
       "WHERE o.status = 'PROCESSING' AND o.processedAt < :staleTime")
int resetStalledProcessing(@Param("staleTime") LocalDateTime staleTime);
```

### 3. 적절한 Stale Threshold 설정

```yaml
outbox:
  stale-threshold-minutes: 5  # 프로덕션: 5분
  # 테스트 환경에서는 더 짧게 (예: 10초)
```

### 4. Prometheus 모니터링

```promql
# Stalled 복구 메트릭
outbox_stalled_recovered_total

# 현재 Pending 수
outbox_pending_count

# 알람 설정
- alert: ZombieOutboxDetected
  expr: outbox_pending_count > 100
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Outbox Zombie 가능성"
```

### 5. 다중 인스턴스 환경 고려

```java
// SKIP LOCKED로 중복 처리 방지
@Query("SELECT o FROM Outbox o WHERE o.status = 'PENDING' ORDER BY o.createdAt")
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHint(value = "org.hibernate.lockMode.pessimistic_lock", name = "javax.persistence.lock.timeout")
@QueryHint(value = "org.hibernate.comment", name = "SKIP LOCKED")
List<Outbox> findPendingForProcessing(Pageable pageable);
```

### 개선 수치 (테스트 결과 기준)
- **Zombie Recovery Rate**: 100% (Stale 항목 자동 복구)
- **Stale Threshold**: 5분 (설정 가능)
- **Recovery Cycle**: 1분 (스케줄러 주기)
- **Data Integrity**: 100% (메시지 손실 없음)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N13-zombie-outbox.md`
- `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`

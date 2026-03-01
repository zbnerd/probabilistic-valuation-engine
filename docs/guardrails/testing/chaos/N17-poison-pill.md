---
id: GR-CHAOS-N17
category: testing/chaos
severity: medium
keywords: [Nightmare, chaos, N17, Poison Pill, DLQ, Dead Letter Queue, Head-of-Line Blocking, ContentHash]
languages: [java, kotlin]
---

# [N17] Poison Pill

## DON'T (장애 원인)

처리 불가능한 메시지(Poison Pill)가 **Consumer를 무한 재시도에 빠뜨려 전체 메시지 처리를 중단**시킵니다 (Head-of-Line Blocking).

### 위험 코드 패턴

```java
// 위험: 무한 재시도 + DLQ 없음
while (true) {
    try {
        handleMessage(message);  // Poison Pill이면 무한 루프
    } catch (Exception e) {
        // 그냥 재시도만 함 ❌
        Thread.sleep(1000);
    }
}
```

### 장애 시나리오

```
[Poison Pill 도착] ← 첫 메시지가 막히면
    ↓
[무한 재시도] → 뒤 메시지 전체 대기
    ↓
[정상 메시지 #2] → 영원히 처리 불가 ❌
```

### 장애 수치
- **HoL Blocking**: 발생 (정상 메시지 처리 불가)
- **Queue Stuck**: 100% (Poison Pill 이후 전체 중단)
- **Recovery Time**: 무한 (수동 개입 필요)

---

## DO (재발 방지)

### 1. ContentHash로 Payload 변조 감지

```java
@Entity
public class DonationOutbox {
    private String contentHash;

    public static DonationOutbox create(String requestId, String eventType, String payload) {
        DonationOutbox outbox = new DonationOutbox();
        outbox.contentHash = computeContentHash(requestId, eventType, payload);
        return outbox;
    }

    public boolean verifyIntegrity() {
        String expected = computeContentHash(requestId, eventType, payload);
        return contentHash.equals(expected);  // 불일치 시 변조 감지!
    }
}
```

### 2. 변조된 Payload는 즉시 DLQ 이동

```java
public void processOutbox() {
    DonationOutbox outbox = outboxRepository.findNext();

    if (!outbox.verifyIntegrity()) {
        handleIntegrityFailure(outbox, "ContentHash mismatch");  // 즉시 DLQ
        return;  // 재시도 안 함
    }

    try {
        externalApi.send(outbox.getPayload());
    } catch (Exception e) {
        handleFailure(outbox, e);
    }
}

private void handleIntegrityFailure(DonationOutbox outbox, String reason) {
    outbox.forceDeadLetter(reason);  // 즉시 DEAD_LETTER 상태
    dlqHandler.handleDeadLetter(outbox, reason);
}
```

### 3. Max Retry 초과 시 DLQ 이동

```java
private void handleFailure(DonationOutbox outbox, Exception e) {
    outbox.incrementRetries();

    if (outbox.getRetryCount() >= MAX_RETRIES) {
        outbox.markDeadLetter();
        dlqHandler.handleDeadLetter(outbox, e.getMessage());
    }
}
```

### 4. Triple Safety Net (DLQ Handler)

```java
@Service
public class DlqHandler {
    public void handleDeadLetter(DonationOutbox entry, String reason) {
        // 1차: DB DLQ
        try {
            dlqRepository.save(DonationDlq.from(entry, reason));
            log.info("📥 [DLQ] 1차 DB 저장 성공");
            return;
        } catch (Exception e) {
            log.error("❌ [DLQ] 1차 DB 저장 실패", e);
        }

        // 2차: File Backup
        try {
            fileBackup.persistToFile(entry, reason);
            log.warn("📁 [DLQ] 2차 File Backup 완료");
            return;
        } catch (Exception e) {
            log.error("❌ [DLQ] 2차 File Backup 실패", e);
        }

        // 3차: Discord Alert (수동 개입 필요)
        discordAlert.sendCriticalAlert("DLQ 저장 실패: " + entry.getRequestId());
        log.error("🚨 [DLQ] 3차 Discord Alert 발송");
    }
}
```

### 5. Prometheus 모니터링

```promql
# DLQ 총 건수
outbox_dlq_total

# 무결성 검증 실패 (변조 시도)
outbox_integrity_failure_total

# 알람 설정
- alert: PoisonPillDetected
  expr: rate(outbox_dlq_total[5m]) > 1
  for: 5m
  labels:
    severity: warning
```

### 개선 수치 (테스트 결과 기준)
- **ContentHash Detection**: 100% (변조 감지)
- **DLQ Transfer Rate**: 100% (Poison pills 격리)
- **HoL Blocking Prevention**: 완료 (정상 메시지 처리 지속)
- **Triple Safety Net**: 모든 3단계 작동 (DB → File → Discord)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N17-poison-pill.md`
- `docs/05_Reports/04_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`

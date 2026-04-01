# 7장: 침묵의 경보 — 장애 중 알림마저 죽었을 때

> ADR-071: Connection Pool 고갈 시 Discord 웹훅 알림 격리
>
> "장애가 났는데 알림도 안 온다면, 그건 이중 재앙이다."

---

## 발생: 2026년 2월 11일, 13:20 KST

3장에서 다룬 커넥션 풀 고갈 장애의 또 다른 측면이다.

장애는 발생했다. 하지만 운영자의 Discord는 조용했다. 알림이 하나도 오지 않았다.

"장애가 안 났나?" — 아니다. 장애는 났다. 시스템은 완전히 마비되어 있었다.

**알림 시스템도 함께 죽은 것이다.**

---

## 탐지: 순환 의존성의 함정

코드를 열어보니 이랬다:

```java
@Service
public class DiscordAlertService {
    private final AlertLogRepository alertLogRepository;  // DB 의존

    public void sendAlert(String title, String message, String severity) {
        // 1. DB에 알림 로그 저장 → 커넥션 필요
        AlertLog log = new AlertLog(title, message, severity);
        alertLogRepository.save(log);        // ← 커넥션 풀 고갈로 BLOCK

        // 2. Discord 웹훅 전송
        discordWebhook.send(formatMessage(log)); // ← 도달하지 못함
    }
}
```

순환 의존성의 완벽한 예시다:

```
장애 발생
  → 커넥션 풀 고갈
    → 알림 서비스가 DB에 로그 저장 시도
      → 커넥션 필요
        → 풀 고갈로 BLOCK
          → Discord 전송 안 됨
            → 운영자가 장애를 모름
              → 대응 지연
                → 장애 악화
```

**소방관이 불에 타고 있었다.**

---

## 분석: 알림의 4가지 실패 모드

이 사건을 계기로 알림 시스템의 모든 실패 가능성을 분석했다.

### 1. DB 의존 (현재 문제)
알림 전송 전에 DB에 로그를 저장. DB가 죽으면 알림도 죽는다.

### 2. Redis 의존 (가능한 문제)
알림 중복 방지를 위해 Redis 사용. Redis가 죽으면 알림이 중복 전송되거나, 반대로 차단될 수 있다.

### 3. 메모리 의존 (가능한 문제)
알림을 메모리에 큐잉. OOM이 발생하면 큐도 사라진다.

### 4. 네트워크 의존 (피할 수 없는 문제)
Discord 자체가 죽으면 어쩔 수 없다. 하지만 이건 최소한 우리가 제어할 수 있는 영역이 아니다.

---

## 대응: 알림의 격리

ADR-071에서 결정한 원칙:

**"알림은 장애의 영향을 받지 않아야 한다."**

### 원칙 1: Stateless 알림

```java
// After: DB에 의존하지 않는 Stateless 알림
public void sendAlert(String title, String message, String severity) {
    // 1. 즉시 Discord 전송 (DB 의존 없음)
    try {
        webClient.post()
            .uri(DISCORD_WEBHOOK_URL)
            .bodyValue(buildDiscordMessage(title, message, severity))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(5));
    } catch (Exception e) {
        log.error("Discord 웹훅 전송 실패 (무시)", e);
        // 실패해도 서비스에 영향 없음
    }

    // 2. 비동기 로그 저장 (Best Effort)
    saveAlertLogAsync(title, message, severity);
}
```

### 원칙 2: Best-Effort 비동기 로깅

```java
@Async
public void saveAlertLogAsync(String title, String message, String severity) {
    try {
        alertLogRepository.save(new AlertLog(title, message, severity));
    } catch (Exception e) {
        log.warn("알림 로그 저장 실패 (무시): {}", e.getMessage());
        // 실패해도 알림은 이미 전송됨
    }
}
```

DB 로그 저장은 Best-Effort다. 성공하면 좋고, 실패해도 알림은 이미 Discord에 갔다.

### 원칙 3: 5분 중복 제거 캐시

장애가 지속되면 같은 알림이 계속 발생한다. "커넥션 풀 고갈!" 알림이 1분에 60번 오면 스팸이다.

```java
private final Cache<String, Boolean> deduplicationCache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)  // 5분 내 동일 알림 무시
    .maximumSize(1000)
    .build();

public void sendAlert(String title, String message, String severity) {
    String deduplicationKey = title + ":" + severity;
    if (deduplicationCache.getIfPresent(deduplicationKey) != null) {
        return;  // 5분 내 이미 전송됨
    }
    deduplicationCache.put(deduplicationKey, Boolean.TRUE);

    // Discord 전송...
}
```

### 원칙 4: 사전 경보

커넥션 풀이 고갈되기 전에 경보를 보낸다.

```java
@Scheduled(fixedDelay = 10000)  // 10초마다
public void monitorConnectionPool() {
    HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
    int active = pool.getActiveConnections();
    int total = pool.getTotalConnections();
    double usageRate = (double) active / total;

    if (usageRate > 0.9) {  // 90% 사용 시 경보
        sendAlert(
            "⚠️ 커넥션 풀 경고",
            String.format("사용률 %.1f%% (active=%d, total=%d)", usageRate * 100, active, total),
            "WARNING"
        );
    }
}
```

---

## 결과: 다음 장애에서의 차이

이 개선 후, 같은 커넥션 풀 고갈 장애가 다시 발생했을 때:

```
Before (ADR-071 전):
  장애 발생 → 알림 전송 안 됨 → 운영자 모름 → 2시간 후 사용자 불만으로 인지

After (ADR-071 후):
  13:19:50 커넥션 풀 사용률 92% → Discord 경보 전송 ✅
  13:20:00 커넥션 풀 고갈 → Discord 장애 알림 전송 ✅
  13:20:10 운영자 인지 → 대응 시작
  13:25:00 장애 복구

인지 시간: 2시간 → 10초
```

---

## 교훈

**1. 알림은 최후의 보루다. 최후의 보루가 먼저 죽으면 안 된다.**

장애를 알리는 시스템이 장애에 종속되면, 장애는 조용히 퍼진다.

**2. 핵심 경로에서 DB를 분리하라.**

알림 전송은 핵심 경로가 아니다. DB 로그 저장은 부가 기능이다. 부가 기능이 핵심 기능을 방해해서는 안 된다.

**3. 사전 경보가 사후 경보보다 낫다.**

"지금 장애 났다"보다 "곧 장애날 것 같다"가 더 유용하다. 90% 임계치 경보로 사전 대응이 가능해졌다.

**4. 중복 제거는 필수다.**

장애 중에 알림이 폭주하면 운영자가 알림을 무시하게 된다. 5분 중복 제거로 핵심 알림만 전달한다.

---

> **다음 장:** [8장: 좋아요의 역습 — Like 도메인 레이스 컨디션](08_like_domain.md)

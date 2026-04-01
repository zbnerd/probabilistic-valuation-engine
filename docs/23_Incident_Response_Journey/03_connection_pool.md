# 3장: 보이지 않는 적 — 커넥션 풀 고갈의 여정

> "커넥션 풀은 묘한 존재다. 평소에는 보이지 않다가, 고갈되는 순간 모든 것이 멈춘다."
>
> — ADR-071, 커넥션 풀 여정기

---

## 발생: 스케줄러가 무한히 스레드를 만들던 날

2026년 2월 11일, 13:20 KST.

Discord에 알림이 와야 했다. 하지만 아무것도 오지 않았다.

운영자가 직접 서버에 접속해 확인했다:

```
HikariCP 상태:
  total: 59
  active: 59
  idle: 0
```

59개의 커넥션이 모두 사용 중. 유휴 커넥션 0개. 시스템은 이미 멈춰 있었다.

---

## 탐지: 두 가지의 실패

이 장애는 두 가지 면에서 실패였다.

**첫째, 장애 자체.** 원인은 스케줄러의 `@Scheduled(fixedRate = ...)` 설정이었다. `fixedRate`는 이전 실행이 끝났는지와 무관하게 고정 주기로 실행한다. DB가 느려지면 실행이 밀리고, 밀린 실행이 새 스레드를 차지하고, 새 스레드가 또 커넥션을 물고... 무한 루프.

```java
// Anti-Pattern: fixedRate 사용
@Scheduled(fixedRate = 5000)  // 5초마다 무조건 실행
public void syncData() {
    // DB 작업이 10초 걸리면?
    // → 5초 후에 새 스레드가 실행됨
    // → 이전 스레드는 아직 커넥션을 물고 있음
    // → 커넥션 누적 → 풀 고갈
}
```

**둘째, 알림의 침묵.** 장애가 발생했는데 Discord 알림이 오지 않았다. 이유를 확인하고 운영자는 얼어붙었다.

```java
// Anti-Pattern: 알림 서비스가 DB에 의존
@Service
public class DiscordAlertService {
    private final AlertLogRepository alertLogRepository;  // DB 의존!

    public void sendAlert(String message) {
        alertLogRepository.save(new AlertLog(message));  // 커넥션 필요 → BLOCK
        discordWebhook.send(message);                      // 도달하지 못함
    }
}
```

알림 서비스가 DB에 로그를 저장하려고 한다. 커넥션이 필요하다. 하지만 커넥션 풀은 이미 고갈되었다. 알림이 전송되지 않는다. 운영자는 장애 사실조차 모른다.

**장애 중에 알림마저 죽은 것.** 이것은 이중 재앙이었다.

---

## 분석: 커넥션 풀의 여정

이 사건을 계기로 커넥션 풀에 대한 전면적인 조사가 시작되었다. 3개월에 걸친 여정이었다.

### Chapter 1: 정렬 불일치

HikariCP 커넥션 풀 사이즈는 25개. Tomcat 스레드 풀은 200개.

```
Tomcat 스레드: 200개 (동시 요청 처리)
HikariCP 커넥션: 25개 (DB 연결)

200 > 25 → 175개의 스레드는 DB를 기다린다
```

이건 속도 불일치가 아니라 **구조적 결함**이었다. 스레드가 커넥션보다 8배 많다. 175개의 스레드가 영원히 기다리는 구조.

**해결:** 공식에 따른 정렬.

```
optimal_pool_size = (CPU cores × 2) + effective_disk_count
t3.small (2 vCPUs): (2 × 2) + 1 = 5

하지만 I/O-bound 워크로드이므로 여유 분 필요:
Local: HikariCP(100) = Tomcat(100)
Production: HikariCP(25) = Tomcat(25)
CI: HikariCP(200) = Tomcat(200)
```

### Chapter 2: 세 개의 심장, 세 개의 커넥션 풀

MySQL: 25개 + Redis: 50개 + MongoDB: 14개 = **89개 이상의 커넥션**.

각 데이터베이스가 독립적인 커넥션 풀을 가진다. 하나의 요청이 세 데이터베이스 모두에 접근하면 3개의 커넥션을 동시에 물고 있어야 한다.

```
요청 1: MySQL(1) + Redis(1) + MongoDB(1) = 3 connections
요청 2: MySQL(1) + Redis(1) + MongoDB(1) = 3 connections
...
요청 30: MySQL(1) + Redis(1) + MongoDB(1) = 3 connections
→ 총 90 connections. 이미 초과.
```

### Chapter 3: Scale-out의 함정

스케일아웃을 시도했다. 5개의 인스턴스를 띄웠다.

```
인스턴스 1: 89 connections
인스턴스 2: 89 connections
인스턴스 3: 89 connections
인스턴스 4: 89 connections
인스턴스 5: 89 connections
───────────────────────
총 445 connections → 데이터베이스 과부하 → 전체 마비
```

스케일아웃은 해결책이 아니었다. 오히려 독이었다.

### Chapter 4: 대이주의 시작

결론은 명확했다. **데이터베이스를 하나로 합쳐야 한다.**

MySQL + Redis + MongoDB → **PostgreSQL 하나로.**

PostgreSQL은 캐시, 락, 메시지 큐(PGMQ), 지속성 — 모든 것을 처리할 수 있었다. 커넥션 풀은 하나면 된다.

```
Before: MySQL(25) + Redis(50) + MongoDB(14) = 89 connections
After:  PostgreSQL(25) = 25 connections
→ 72% 감소
```

### Chapter 5: Advisory Lock의 누수

PostgreSQL로 이관하면서 분산 락도 바꿨다. Redis 분산 락 → PostgreSQL Advisory Lock.

그런데 또 문제가 있었다. `pg_advisory_lock`은 세션 스코프다. 커넥션이 반환되어도 락이 풀리지 않는다. HikariCP는 커넥션을 재사용하니까, 이전 세션의 락이 새 요청에까지 이어진다.

```sql
-- Anti-Pattern: 세션 스코프 락
SELECT pg_advisory_lock(12345);  -- 획득
-- 트랜잭션 종료
-- 커넥션 반환 (락은 여전히 잠금 상태!)
-- 다른 요청이 같은 커넥션을 받음
-- → 교착상태
```

**해결:** `pg_try_advisory_xact_lock` — 트랜잭션 스코프.

```sql
-- 해결: 트랜잭션 스코프 락
SELECT pg_try_advisory_xact_lock(12345);  -- 트랜잭션 내에서만 유효
-- 트랜잭션 커밋/롤백 시 자동 해제
-- 커넥션 반환 시 락도 해제됨 ✅
```

### Chapter 6: 세 개의 Outbox 스케줄러

Outbox 패턴을 구현하면서 3개의 스케줄러가 각각 커넥션을 물고 있었다.

```
Outbox Scheduler 1: 2-3 connections (항상 대기)
Outbox Scheduler 2: 2-3 connections (항상 대기)
Outbox Scheduler 3: 2-3 connections (항상 대기)
───────────────────────────────────
총 6-9 connections이 항상 점유됨
```

25개의 커넥션 중 6-9개가 스케줄러 전용. 사용자 요청은 16-19개로 제한된다.

### Chapter 7: PGMQ 통합

모든 Outbox를 PGMQ로 통합했다. 42개 파일이 삭제되었다. 3개의 스케줄러가 1개의 PgmqWorker로 통합되었다.

```
Before: 3 schedulers × 2-3 connections = 6-9 connections
After:  1 PgmqWorker × 1 connection = 1 connection
→ 커넥션 5-8개 절약
```

---

## 최종 결과

```
커넥션 풀 여정 요약:

시작: 89+ connections (MySQL 25 + Redis 50 + MongoDB 14)
  ↓ 정렬 불일치 해결
  ↓ PostgreSQL 통합 (3 → 1)
  ↓ Advisory Lock 스코프 수정
  ↓ Outbox → PGMQ 통합
최종: 25 connections (PostgreSQL 하나)

RPS: 97 → 7,347 (76배 향상)
p99: 4,100ms → 36ms (99% 감소)
```

---

## 교훈

**1. 커넥션 풀은 보이지 않는 병목이다.**

사용자는 "느리다"고만 한다. 원인이 커넥션 풀 고갈이라는 것을 파악하는 데 시간이 걸렸다.

**2. 정렬이 중요하다.**

Tomcat 스레드 200개에 HikariCP 25개는 불일치다. 수영장에 200명이 들어가려 하는데 탈의실이 25개인 것과 같다.

**3. 복잡성은 숨겨진 비용이다.**

3개의 데이터베이스 = 3개의 커넥션 풀 = 3배의 복잡성. 단순화가 최적화다.

**4. 스케줄러는 조용한 자원 소모자다.**

백그라운드 스케줄러가 항상 커넥션을 물고 있으면, 사용자 요청이 몰릴 때 커넥션이 부족하다.

---

> **다음 장:** [4장: 뇌우 — 캐시 스탬피드와 SingleFlight의 깨달음](04_cache_stampede.md)

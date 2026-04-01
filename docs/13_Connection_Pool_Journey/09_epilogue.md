# 에필로그: 하나의 풀, 하나의 데이터베이스

> "단순함이 궁극의 정교함이다." — 레오나르도 다빈치

## 여정의 끝에서

2026년 4월 1일, 마지막 PR(#690)이 머지되었다. 커넥션 풀 여정이 끝났다.

### 여정 한눈에 보기

```
2026년 1월 — 3개 DB, [추정] 89+ connections, 97 RPS
       ↓
2026년 2월 — HikariCP 정렬 경고 포착
       ↓
2026년 3월 8일 — 풀 사이즈 정렬 (PR #572)
       ↓
2026년 3월 9일 — PostgreSQL Migration Foundation (PR #578)
       ↓
2026년 3월 10일 — Redis-free scale-out (PR #584)
       ↓
2026년 3월 11일 — Redis/MySQL/MongoDB 제거 (Issues #589-591)
       ↓
2026년 3월 29일 — Advisory Lock xact-scope 전환 (PR #628-631)
       ↓
2026년 3월 31일 — Outbox → PGMQ Phase 0-2 (PR #685-687)
       ↓
2026년 4월 1일 — Outbox → PGMQ Phase 3-5 (PR #688-690)
       ↓
2026년 4월 — 1개 DB, [실측] 25 connections, 7,347 RPS
```

## 최종 아키텍처

```
Before:
Client → Spring Boot
           ├── Redis 7.0 (Master + Slave + 3 Sentinel)     ← [실측] Redisson 64 conn
           ├── MySQL 8.0                                  ← [실측] HikariCP 25 conn
           ├── MongoDB                                    ← [미확인] MongoClient ? conn
           ├── 3 Outbox Schedulers (개별 커넥션 폴링)     ← [추정] ~3-9 conn
           └── Nexon API
           [추정] 총 89+ connections, 3 DB, 42개 관리 파일

After:
Client → Spring Boot
           ├── PostgreSQL (cache, lock, pub/sub, persistence, queue)
           │     └── [실측] HikariCP max 25 connections
           │           ├── [추정] Business queries          ~12 conn
           │           ├── [추정] PGMQ send/read/archive     ~5 conn
           │           ├── [추정] Advisory Lock (xact)       ~2 conn
           │           ├── [추정] LISTEN/NOTIFY              ~2 conn
           │           └── [추정] Worker polling             ~4 conn
           └── Nexon API
           [실측] 총 25 connections, 1 DB, PgmqWorker 통합
```

## 교훈

### 1. 커넥션은 공유 자원이다

커넥션 풀은 메모리, CPU와 같은 **공유 자원**이다. 한 곳에서 낭비하면 다른 곳이 굶주린다.

```
[예시] 잘못된 생각: "이 스케줄러는 커넥션 1개만 쓰니까 괜찮아"
[예시] 올바른 생각: "1개 커넥션 × 3 스케줄러 = 풀의 12%를 상시 점유.
               다른 요청이 그 커넥션을 기다릴 수 있어."

(이 예시는 25개 풀 기준입니다. 실제 비율은 환경에 따라 다릅니다)
```

### 2. 풀 사이즈 정렬은 필요조건, 충분조건이 아니다

```
필요조건: HikariCP max ≥ 동시 DB 접근 스레드 수
충분조건: 아키텍처 자체가 커넥션을 적게 쓰도록 설계
```

풀을 키우는 것은 증상 치료다. 근본 원인을 해결하려면 커넥션을 적게 쓰는 구조로 바꿔야 한다.

### 3. Outbox는 안전하지만 비싸다

Outbox 패턴은 "메시지를 잃지 않는다"는 보장을 준다. 하지만 그 대가는:

- INSERT + SELECT + UPDATE × 3 Outbox = 추가 커넥션 + 추가 쿼리
- 스케줄러 3개의 폴링 = 상시 커넥션 점유
- 42개 파일의 유지 보수 비용

PGMQ가 트랜잭션에 참여한다면, Outbox 없이도 같은 보장을 얻을 수 있다. `pgmq.send()`가 `COMMIT`과 함께 성공하거나 `ROLLBACK`과 함께 사라진다.

### 4. 락의 수명 주기와 커넥션의 수명 주기는 일치해야 한다

```
Session-scope lock: 락이 커넥션보다 오래 살 수 있음 → 누수 위험
Transaction-scope lock: 락이 트랜잭션과 함께 시작하고 끝남 → 누수 불가능
```

HikariCP와 세션 락은 근본적으로 호환되지 않는다. 트랜잭션 스코프 락만이 안전하다.

### 5. 단일 DB = 단일 풀 = 단순한 모니터링

```
Before: HikariCP(Redis) + HikariCP(MySQL) + MongoClient + Redisson = 4개 풀 모니터링
After:  HikariCP(PostgreSQL) = 1개 풀 모니터링
```

`/actuator/metrics/hikaricp.connections.active` 하나만 보면 전체 커넥션 상태를 파악할 수 있다.

## 원칙: 커넥션 풀 5계명

이 여정에서 도출한 5가지 원칙:

1. **정렬하라**: HikariCP `maximum-pool-size` = Tomcat `threads.max` (I/O-bound)
2. **측정하라**: `register-mbeans: true`, `leak-detection-threshold: 60000`은 필수
3. **통합하라**: 여러 DB의 커넥션 풀을 하나로 합쳐라. 유휴 커넥션을 공유할 수 있다
4. **해제하라**: 락은 트랜잭션 스코프로. 세션 락은 커넥션 누수의 원인
5. **단순하라**: Outbox가 필요 없으면 제거하라. PGMQ가 TX 참여하면 Outbox는 중복

## 성과

| 지표 | 여정 시작 | 여정 끝 | 변화 |
|------|----------|---------|------|
| 커넥션 (인스턴스당) | [추정] 89+ | [실측] 25 | **-72%+** |
| 데이터베이스 | 3 | 1 | **-67%** |
| Outbox 스케줄러 | 3 | 0 | **-100%** |
| 관리 파일 | ~42 | ~15 | **-64%** |
| RPS | 97 | 7,347 | **+7,472%** |
| p99 Latency | 4,100ms | 36ms | **-99%** |
| 에러율 | 59.7% | 0% | **-100%** |

## 남은 과제

완벽하지 않다. 아직 해결할 과제가 있다:

1. **PGMQ Archive 정책**: 30일 보관 후 자동 삭제. 장기 보관이 필요한 메시지에 대한 정책 필요
2. **Scale-out 시 PGMQ 경합**: 다중 인스턴스에서 같은 큐를 읽을 때 visibility timeout 내 처리 완료 보장
3. **커넥션 풀 동적 조정**: 트래픽 패턴에 따라 `maximum-pool-size`를 동적으로 조정하는 메커니즘
4. **PostgreSQL 커넥션 한계**: 인스턴스가 늘어나면 여전히 `max_connections`에 도달. PgBouncer 도입 고려

## 마무리

이 여정은 "더 많은 기술을 도입해서" 해결한 것이 아니다. **더 적은 기술로** 해결했다.

3개의 데이터베이스를 1개로, 3개의 아웃박스를 PGMQ로, [추정] 89+개의 커넥션을 [실측] 25개로.

결국 소프트웨어 엔지니어링이란 **무엇을 추가하는 것이 아니라, 무엇을 제거하는 것**인지도 모른다.

---

> *"Perfection is achieved not when there is nothing more to add, but when there is nothing left to take away."*
> *— Antoine de Saint-Exupery*

# 커넥션 풀 여정기: 분산된 풀에서 하나로

> **Probabilistic Valuation Engine**의 커넥션 풀 최적화 여정
> 2026년 2월 ~ 4월, HikariCP 고갈에서 PGMQ 통합까지

---

## 왜 이 이야기를 하는가

성능 최적화 여정(97→7,347 RPS)의 그림자에는 **커넥션 풀**이라는 숨은 주인공이 있었다. RPS가 오를 때마다 커넥션이 부족해졌고, 스케일아웃을 시도할 때마다 풀이 고갈되었다. 결국 3개의 데이터베이스와 3개의 아웃박스 스케줄러가 각각 물고 있던 커넥션을 **PostgreSQL 단일 풀로 통합**하기까지의 과정을 기록한다.

이 책은 [성능 여정기](../06_Performance_Journey/README.md)의 자매서적이다. 성능 여정기가 "RPS"를 주인공으로 삼았다면, 이 책은 **"커넥션"**을 주인공으로 삼는다.

## 책의 구성

| 장 | 제목 | 핵심 사건 | 기간 |
|----|------|----------|------|
| [프롤로그](./00_prologue.md) | 세 개의 데이터베이스, 분산된 커넥션 풀 | 초기 인프라와 커넥션 구조 | 2026년 1월 |
| [1장](./01_misalignment.md) | 첫 번째 경고: HikariCP 정렬 실패 | 풀 사이즈 10 vs 스레드 200 | 2026년 2월 |
| [2장](./02_alignment_fix.md) | 정렬: 공식을 세우다 | `(CPU×2)+disk` 공식과 모니터링 | 2026년 3월 8일 |
| [3장](./03_scale_out_wall.md) | Scale-out의 벽 | 5대 인스턴스에서 RPS 하락 | 2026년 3월 초 |
| [4장](./04_great_migration.md) | 대이주: 3개 DB에서 1개로 | MySQL+Redis+MongoDB → PostgreSQL | 2026년 3월 9~11일 |
| [5장](./05_advisory_lock.md) | 숨은 병목: 락이 훔친 커넥션 | Session 락 → Xact 락 전환 | 2026년 3월 29일 |
| [6장](./06_outbox_problem.md) | 아웃박스의 대가 | 3개 스케줄러 × 3개 커넥션 폴링 | 2026년 3월 |
| [7장](./07_pgmq_unification.md) | PGMQ: 하나의 풀로 모든 것을 | Outbox 3개 → PGMQ 통합 (5 Phase) | 2026년 3월 31일~4월 1일 |
| [8장](./08_code_story.md) | 코드로 보는 여정 | 스케줄러 통합 전후 코드 비교 | - |
| [에필로그](./09_epilogue.md) | 하나의 풀, 하나의 데이터베이스 | 교훈과 원칙 | - |

## 커넥션 변화 한눈에 보기

```
Connection Pool Evolution (2026-01 ~ 2026-04)

Before (3 DBs, 단일 인스턴스):
┌─────────────────────────────────────────────┐
│  [실측] HikariCP (MySQL):     max 25 conn   │
│  [실측] Redisson (Redis):     max 64 conn   │
│  [미확인] MongoClient (Mongo): pool size ?  │
│─────────────────────────────────────────────│
│  Total: 89+ connections × 3 DBs             │
│  + 3 Outbox Schedulers polling separately   │
└─────────────────────────────────────────────┘

After (PostgreSQL Only, 단일 인스턴스):
┌─────────────────────────────────────────────┐
│  [실측] HikariCP (PostgreSQL): max 25 conn  │
│    ├── Business queries                     │
│    ├── PGMQ send/read/archive/delete        │
│    ├── Advisory Lock (xact scope)           │
│    ├── LISTEN/NOTIFY                        │
│    └── All workers via PgmqWorker           │
│─────────────────────────────────────────────│
│  Total: 25 connections × 1 DB               │
│  Outbox 제거, PGMQ가 동일 커넥션에서 처리   │
└─────────────────────────────────────────────┘
```

## 핵심 수치

| 지표 | 변경 전 | 변경 후 | 개선 |
|------|---------|---------|------|
| 총 커넥션 수 (인스턴스당) | [실측] 89+ (25+64+?) | [실측] 25 | **72%+ 감소** |
| 데이터베이스 수 | 3 | 1 | **3→1** |
| Outbox 스케줄러 | 3개 (개별 폴링) | 0개 (PGMQ 통합) | **완전 제거** |
| 락 커넥션 누수 위험 | Session-scope (수동 해제) | Xact-scope (자동 해제) | **원자적 해제** |
| 트랜잭션 원자성 | Outbox 경유 (비원자적) | PGMQ same-TX | **원자적 보장** |

## 관련 자료

- [성능 여정기](../06_Performance_Journey/README.md) — RPS 관점의 자매 서적
- [Outbox → PGMQ 마이그레이션 계획](../09_Plans/outbox-to-pgmq-migration.md)
- [ADR-014: Connection Pool Alignment](../01_ADR/ADR-335-connection-pool-alignment.md)
- [ADR-314: PostgreSQL 단일 DB 전략](../01_ADR/ADR-314-postgresql-single-db-strategy.md)

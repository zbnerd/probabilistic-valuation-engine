# 97 RPS에서 7,347 RPS까지: 성능 여정기

> **Probabilistic Valuation Engine**의 성능 최적화 여정을 담은 기술 서사
> 2026년 1월 ~ 3월, 97 RPS에서 7,347 RPS까지의 기록 (수십만 실데이터 기준)

---

## 책의 구성

이 책은 결과가 아니라 **과정**을 이야기합니다. 어떤 문제가 있었고, 무엇을 고민했으며, 어떻게 해결했고, 그 결과 어떤 숫자가 나왔는지. 그리고 그 해결이 어떻게 또 다른 문제를 낳았는지.

각 장은 하나의 "문제-해결" 사이클입니다.

| 장 | 제목 | RPS 변화 | 기간 |
|----|------|----------|------|
| [프롤로그](./00_prologue.md) | 97 RPS에서 시작하다 | - | 2026년 1월 |
| [1장](./01_chaos_baseline.md) | 첫 번째 측정: 현재가 얼마나 느린가 | 223 RPS | 1월 20일 |
| [2장](./02_singleflight_regression.md) | 역설적 회귀: 최적화가 성능을 56% 떨어뜨리다 | 97 RPS | 1월 24일 |
| [3장](./03_l1_fast_path.md) | 발견: 캐시 히트인데 왜 느리지? | 555 RPS | 1월 24일 |
| [4장](./04_write_behind_buffer.md) | DB 저장이 발목을 잡다 | 674 RPS | 1월 25일 |
| [5장](./05_parallel_presets.md) | 3개를 한 번에: 병렬 계산의 힘 | 965 RPS | 1월 26일 |
| [6장](./06_stateless_tradeoff.md) | 정합성의 대가: 일관성 vs 속도 | 325 RPS | 1월 27일 |
| [7장](./07_auto_warmup.md) | 차가운 시작: 캐시 웜업의 중요성 | 940 RPS | 1월 27일 |
| [8장](./08_great_migration.md) | 대이주: Redis, MySQL, MongoDB를 버리다 | Micro-Batching 대폭 향상 | 2~3월 |
| [9장](./09_postgresql_notify.md) | 최후의 도약: PostgreSQL NOTIFY | 10,994 RPS* | 3월 19~20일 |
| [10장](./10_real_data_challenge.md) | 현실의 벽: 수십만 데이터로 검증하다 | **~7,347 RPS** | 3월 22~24일 |
| [11장](./11_fanout_admission_control.md) | 보이지 않는 폭발: Fan-Out과 Admission Control | Fan-Out 보호 구현 | 3월 28일 |
| [에필로그](./12_epilogue.md) | 97에서 7,347, 그리고 그 너머 | - | - |

> \* 10,994 RPS는 빈 DB에서의 이상적 수치입니다. 실제 운영 환경(수십만 rows)에서는 ~7,347 RPS.

---

## 성능 변화 한눈에 보기

```
RPS Evolution (2026-01-20 ~ 2026-03-30)

  11,000 ┤                                          ╭─── 10,994 (빈 DB, 이상적)
   9,000 ┤                                      ╭───╯
   7,000 ┤                                  ╭───╯
   7,000 ┤ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ╯ ─ ─ ─ ─ ─ ─ ─ ─ 7,347 (실데이터) ◀
   3,000 ┤
   1,000 ┤            ╭───╭───╭─── 965
       0 ┼───╭───╭───╯   │   │
     325 ┤   │   │   555  │   674
      97 ┤   │       │
         └───┴───────┴────────────────────────────────────────────────────
          Jan 20  Jan 24  Jan 25  Jan 26  Jan 27   Mar 19   Mar 24  Mar 30
          Chaos   Single  Fast    Write   ADR      NOTIFY   Real    Epilogue
          Base    flight  Path    Behind  Refactor          Data
```

## 핵심 수치

| 지표 | 시작 | 최종 (실데이터) | 개선 |
|------|------|----------------|------|
| RPS | 97 | **7,347** | **76배** |
| p99 지연 | 4,100ms | 36ms | **99% 감소** |
| 인프라 | Redis + MySQL + MongoDB | PostgreSQL 단일 | **3개 DB → 1개** |
| 에러율 | 59.7% | 0% | **완전 제거** |
| DB rows | ~100 | 200k~300k | **실 운영 데이터** |
| Scale-out | 불가 | **선형 확장 준비 완료** | LISTEN/NOTIFY |

## 관련 자료

- [ADR-027: Load Test Performance Evolution](../01_ADR/ADR-342-load-test-performance-evolution.md)
- [ADR-086: Performance Baseline Analysis](../01_ADR/ADR-364-performance-analysis-20260324.md)
- [ADR-028: 300k Bulk Loading](../01_ADR/ADR-343-bulk-loading-300k-characters.md)
- [부하 테스트 보고서 모음](../05_Reports/05_06_Load_Tests/)

---

## 용어집

| 용어 | 설명 |
|------|------|
| **TieredCache** | L1(Caffeine) + L2(PostgreSQL UNLOGGED) 2계층 캐시 구조 |
| **SingleFlight** | 동일 키에 대한 중복 계산을 방지하는 패턴. Leader가 계산하면 Follower는 결과 공유 |
| **PGMQ** | PostgreSQL Message Queue. PostgreSQL 익스텐션 기반 메시지 큐 |
| **LISTEN/NOTIFY** | PostgreSQL의 비동기 알림 메커니즘. 캐시 무효화 전파에 사용 |
| **Advisory Lock** | PostgreSQL의 애플리케이션 레벨 분산 락. `pg_try_advisory_xact_lock` 사용 |
| **Write-Behind Buffer** | 쓰기를 비동기로 버퍼링하여 DB 부하를 줄이는 패턴 |
| **Micro-Batching** | 짧은 시간 창의 요청을 모아 배치 쿼리로 처리하는 최적화 |
| **Fan-Out** | 서로 다른 키의 동시 요청이 발생하는 시나리오 |
| **Admission Control** | 시스템 과부하를 방지하기 위해 요청을 제어하는 메커니즘 |
| **Cache Stampede** | 캐시 만료 시 다수 요청이 동시에 DB를 조회하는 현상 |
| **Cold Start** | 캐시가 비어있는 상태에서 시작하는 것 |
| **Warm-up** | 캐시를 미리 채우는 작업 |

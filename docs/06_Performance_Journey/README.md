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
| [에필로그](./11_epilogue.md) | 97에서 7,347, 그리고 그 너머 | - | - |

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
- [ADR-086: Performance Baseline Analysis](../01_ADR/ADR-086-performance-analysis-20260324.md)
- [ADR-028: 300k Bulk Loading](../01_ADR/ADR-343-bulk-loading-300k-characters.md)
- [부하 테스트 보고서 모음](../05_Reports/04_06_Load_Tests/)

# ADR-740: daily_full_pipeline 퇴역 (schedule=None, morning_chain 이 정규 체인)

- Status: Accepted
- Date: 2026-06-27
- Owner: maple-pipeline
- Related: morning_chain_pipeline, ADR-739, daily_collection_pipeline

---

## 1. Background / Problem

### Background

- `daily_full_pipeline` = `0 18 * * *` (03:00 KST) 풀체인: ranking → character_basic(once) → item_equipment(once) → cleanup (모두 wait_for_completion=True).
- `morning_chain_pipeline` = 동일 `0 18 * * *` (03:00 KST): stop_loop → ranking → ocid → character_basic(once) → item_equipment(**infinite**) + 시작 감지 sensor.
- cleanup 은 `daily_cleanup_pipeline` (`0 */6 * * *`) 이 독자 스케줄로 정상 동작.

### Problem

- 두 DAG 가 같은 cron 동시 발화 → 같은 phase slot 경쟁.
- morning_chain 이 먼저 점유 → daily_full 의 `trigger_character_basic` 매일 timeout fail (06-25, 06-26 연속).
- item_equipment/cleanup 은 upstream_failed. daily_full 은 사실상 매일 전체 fail.
- daily_full 은 morning_chain 의 중복 legacy (morning_chain 이 ocid 추가 + infinite loop 로 상위 호환).

### Goal

- 03:00 KST 중복 발화 제거. morning_chain 을 정규 스타트업 체인으로 단일화.

---

## 2. Decision

> `daily_full_pipeline` 의 schedule 을 `0 18 * * *` → `None` 으로 퇴역 (repo 컨벤션: daily_collection 동일 패턴). DEPRECATED docstring 로 morning_chain 을 정규 후계 DAG 로 명시.

```text
daily_full_pipeline.py:
  schedule = None  # retired; morning_chain_pipeline owns 03:00 KST (infinite item-equipment)
  docstring: DEPRECATED 2026-06-27 → use morning_chain_pipeline
```

근거:
- morning_chain 이 daily_full 의 모든 phase 커버 + ocid + infinite item-equipment (사용자 요구 = loop).
- cleanup 은 daily_cleanup_pipeline 독자 스케줄 → daily_full 의 cleanup trigger 없어도 정상.
- 역참조 없음 (어느 DAG 도 daily_full 을 trigger 안 함).
- daily_collection 퇴역 방식(schedule=None + DEPRECATED docstring) 과 일관.

---

## 3. Trade-offs

### Sensitivity

* 수동 `airflow dags trigger daily_full_pipeline` 은 여전 가능 (schedule=None 은 스케줄만 끔, 정의는 보존). finite once-mode 풀체인이 필요하면 수동 실행.
* 과거 run history 는 metadata DB 에 잔존 (DAG 는 parse 됨).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| schedule=None (정의 보존) | 03:00 충돌 제거, reversible, repo 컨벤션 일관 | 파일 잔존 (수동 실행 가능 = 의도) |

### Risk

* 없음. morning_chain + daily_cleanup 이 전 커버리지 유지.

### Non-Risk

* cleanup — 독자 스케줄.
* morning_chain — 변경 없음.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| 03:00 발화 DAG 수 | 2 → 1 | daily_full retired, morning_chain 단일 |
| daily_full 일일 fail | 매일 → 0 | schedule=None 로 발화 중단 |

### Observed Result

* 코드/구문: Python AST parse OK, scheduler DagBag import clean.
* 런타임: 다음 03:00 KST(06-28) 에 daily_full 미발화, morning_chain 단독 success 관측 예정.

---

## 5. Summary

> 매일 fail 하던 daily_full_pipeline 을 schedule=None 퇴역. morning_chain(infinite item-equipment) 이 03:00 KST 정규 단일 체인. cleanup 은 독자 daily_cleanup 유지.

# ADR-726: Airflow Trigger Task — PythonOperator over HttpOperator

- Status: Accepted
- Date: 2026-06-14
- Owner: maple-pipeline

---

## 1. Background / Problem

### Background

`daily_collection_pipeline.trigger_daily_collection` HttpOperator was failing
on every retry with `AirflowException: 409:`. The DAG was designed to treat
409 CONFLICT as an idempotent success (another run already active on ext-api
→ accept and correlate against the active runId). The `response_check` callback
was meant to make this work.

### Problem

`HttpOperator.response_check` is **unreachable for 4xx responses**. The
`HttpHook.run()` call internally routes through `run_and_check()` →
`check_response()` → `response.raise_for_status()`, which raises
`AirflowException` for any 4xx/5xx **before** the operator's `response_check`
callback is ever invoked. There is no operator-level configuration that
disables this hook-level raise.

Consequence:
- 3 consecutive scheduled collection runs (06-12, 06-13) failed with
  `AirflowException: 409:` after exhausting 120 retries × 60s = 2h.
- Each "fix attempt" (custom `is_accepted_response` accepting 409) was
  dead code — the callback never ran.

### Goal

A trigger task that:
1. Accepts 200/202/409 as success.
2. Captures the `runId` for downstream `wait_for_completion` correlation.
3. Fails fast on real errors (4xx other than 409, 5xx, network failure).

---

## 2. Decision

> Use `PythonOperator` with `requests.post()` directly instead of
> `HttpOperator` for the trigger task. Apply `PythonSensor` (mode=reschedule)
> for the wait_for_completion poll. Use `BaseHook.get_connection("external_api")`
> in every Python call site instead of hardcoded
> `http://host.docker.internal:8081`.

```text
trigger_daily_collection = PythonOperator(
    python_callable=trigger_daily_collection_fn,
    retries=0,                 # idempotent; 409 = success
    execution_timeout=60s,
    do_xcom_push=True,
)

trigger_daily_collection_fn(**context):
    response = requests.post(f"{get_external_api_base()}/api/internal/trigger/daily")
    if 200/202: return response.json()        # xcom → runId
    if 409:     return active_run_from_status()  # xcom → runId
    else:      raise AirflowException(...)    # real failure

wait_for_completion = PythonSensor(
    python_callable=_is_run_terminal,
    mode="reschedule",        # frees worker slot between pokes
    poke_interval=60, timeout=4h,
)
```

---

## 3. Trade-offs

### Sensitivity

* ext-api response shape on 409 (must include discoverable runId)
* Airflow Connection `external_api` must be registered with `host:port`
* LocalExecutor availability for worker slot during 4h poll
  (`mode="reschedule"` mitigates this)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| PythonOperator + requests | 4xx 흐름을 우리 코드가 직접 통제. 409 idempotent success 명시 가능. Connection config 통합. | HttpOperator가 제공하던 retry/timeout/logging 템플릿을 직접 작성해야 함 |
| PythonSensor mode=reschedule | 4h 대기 중 worker slot 반환. 다른 task 실행 가능. | Reschedule 시 매 poke마다 슬롯 획득/해제 오버헤드. LocalExecutor parallelism=32에서 32+ 동시 sensor 시 병목 |
| Hardcoded host → BaseHook | Connection 변경 시 코드 수정 불필요. 테스트 환경 분리 용이 | Connection 미등록 시 task fail (graceful degradation 없음) |

### Risk

* `trigger_daily_collection_fn`의 `requests.post` timeout=30s 동안
  worker가 점유됨. 1 task라 무시 가능.
* `wait_for_item_equipment_cycle`의 Kafka consumer는 여전히
  `consumer_timeout_ms=120min` 단일 블로킹 패턴. 별개 개선 여지.
* Airflow Scheduler는 `LocalExecutor` BrokenPipeError로 main loop이
  주기적으로 죽는 별개 이슈 (이 ADR 범위 밖).

### Non-Risk

* HttpHook의 raise_for_status 동작을 더 이상 추측할 필요 없음
  (직접 4xx 분기 처리).
* `is_accepted_response` 단일 함수가 sensor/operator 의미 혼재하던
  문제 제거 (함수 자체 삭제).

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After |
| ------ | ----: | ----: |
| Trigger task failure (3 scheduled runs) | 3/3 failed at 2h | n/a |
| Trigger task duration | 0.99s (fast fail at 409) | 0.7s (success, returns runId) |
| `response_check` 호출 도달 여부 | ❌ 도달 불가 (hook raise) | n/a (callback 제거) |
| Connection host 하드코딩 | `http://host.docker.internal:8081` × 2 | `BaseHook.get_connection` × 2 |

### Observed Result

`airflow tasks test daily_collection_pipeline trigger_daily_collection
2026-06-14T07:00:00+00:00`:

```
Marking task as SUCCESS. dag_id=daily_collection_pipeline,
task_id=trigger_daily_collection, run_id=manual__2026-06-14T07:00:00+00:00
Returned value: {'runId': '20260614-132225-262825530', 'status': 'ALREADY_RUNNING'}
```

---

## 5. Summary

> HttpOperator의 `response_check`는 hook raise 이후에야 호출되므로 4xx 흐름
> 제어가 불가능하다. trigger task는 PythonOperator + requests로 직접 호출하고,
> poll task는 PythonSensor(reschedule)로 worker slot을 반환하도록 분리했다.

# 24. 트러블슈팅 케이스북 (Troubleshooting Casebook)

> `docs/ai-traces/` 세션 기록 + 코드베이스 + ADR + git history 에서 추출한
> **문제 → 원인 → 해결 → 왜 그 방법 / 대안** 사례 모음.
> 2026-06-09 ~ 2026-06-28 (약 3주, ~110 세션) 운영·개발 중 발생한 실제 장애·버그·설정 결함.

---

## 왜 이 문서인가

ADRsms *결정(Decision)* 을 기록하지만, 그 결정에 이르는 **에러 증상·재현·디버깅 과정** 은 세션 트레이스에만 남는다.
이 케이스북은 그 "증상 → 원인 → 해결 → 대안 검토" 사이클을 주제별로 재구성해 동일 실수 반복을 막고
장애 대응 속도를 높인다. 각 사례는 **실측 에러 메시지·세션 ID·commit·ADR** 를 인용한다.

---

## 주제별 색인

| # | 파일 | 주제 | 대표 사례 |
|---|------|------|-----------|
| 01 | [01_async_concurrency.md](01_async_concurrency.md) | 비동기·동시성·가상스레드·락 | CF `.get()` pinning, `LockAspect` 예외 언래핑, `BackpressureLimiter`, recursion StackOverflow, coroutines-reactor 누락 |
| 02 | [02_memory_streaming.md](02_memory_streaming.md) | 메모리·OOM·스트리밍 I/O | OCID 매핑 OOM, GzipJsonl OOM, `catch(Exception)` 가 OOM 삼킴, sync `s3.putObject` 병목, off-heap cache |
| 03 | [03_pipeline_data_correctness.md](03_pipeline_data_correctness.md) | 파이프라인 데이터 정합성 | chunk-key 불일치, chunk body 스키마 mismatch, 잘못된 upstreamRunId, result writer pipe race, contentLength 음수 |
| 04 | [04_orchestration_airflow.md](04_orchestration_airflow.md) | 오케스트레이션·Airflow | HttpOperator 409 dead code, task timeout, connection 미등록, in-process cron vs DAG slot race, daily_full 중복 |
| 05 | [05_loop_lifecycle_observability.md](05_loop_lifecycle_observability.md) | 무한루프 생명주기·관측성 | loop 사망(upstream null), sensor `iterationCount>=1` timeout, log rotate-out 관측 회귀 |
| 06 | [06_infra_deploy_migration.md](06_infra_deploy_migration.md) | 인프라·배포·마이그레이션 | nohup→docker network duality, MinIO `listByPrefix` 1000 truncation, SA policy, CI MinIO, Nexon pool 튜닝 |

---

## 읽는 법

각 사례 구조:
- **문제/에러** — 증상 + 실측 에러 메시지/stacktrace
- **원인** — 근본 원인 (1-2문장)
- **해결** — 적용한 변경 (commit + ADR)
- **왜 이 방법 / 대안** — 해당 접근 선택 이유 + 기각한 대안

인용 키:
- `Session 202606XX-XXXXXX` — `docs/ai-traces/` 세션
- `commit <sha>` — git
- `ADR-XXX` — `docs/01_ADR/`

---

## 교차 교훈 (Cross-cutting lessons)

1. **`catch (Exception)` vs `Throwable`** — OOM/StackOverflow(`Error`) 를 삼키면 진짜 원인이 묻힌다. 단일 writer/sink 코루틴은 `Throwable` 잡아야 (사례 02-3).
2. **`HttpOperator` + 멱등 4xx = 불가능** — `response.raise_for_status()` 가 callback 전에 throw. 멱등 트리거는 `PythonOperator` (사례 04-1).
3. **in-memory 상태 + 장기 실행 루프 = 사망** — PhaseLoopController in-memory state 가 restart/refresh 창에 날아감. 관측 로그가 없으면 원인 불가 (사례 05).
4. **동일 cron 중복 DAG = 매일 fail** — control plane 이전 후 legacy scheduler 가 잔존하면 slot 경쟁. 퇴역은 `schedule=None` (사례 04-4, 04-5).
5. **log rotation 이 진단을 가른다** — 장기 loop lifecycle 은 retention 충분해야 포착 (사례 05-3).
6. **동기 bridge 가 비동기 체인을 깬다** — core port 가 sync return 이면 모든 caller 가 `.get()` pinning. `*Async` big-bang + CI grep gate (사례 01-1).

---

*생성: 2026-06-28 · 소스: docs/ai-traces (110 sessions) + docs/01_ADR (192) + git log*

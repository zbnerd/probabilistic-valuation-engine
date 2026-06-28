# 트러블슈팅 케이스북 — 문제를 정의하고 측정하고 구조적으로 해결한 기록

> 단일 노드에서 하루 ~600K 캐릭터의 장비 기대값을 계산하는 비동기 ETL 파이프라인.
> 3주(2026-06-09 ~ 06-28)간 발생한 실제 장애·버그·회귀를 **증상 → 원인 → 해결 → 왜 그 방법 / 대안** 으로 재구성.
> 각 사례는 ADR·commit·세션 ID 로 검증 가능한 근거를 인용한다.

---

## 시스템 맥락 (왜 이 문제들이 의미 있는가)

메이플스토리 전체 랭킹 IGN(~600K) 의 장비 데이터를 매일 03:00 KST 에 수집·계산·동기화하는 파이프라인이다.
한 패스 = item-equipment IGN 560K 를 ~62분(150 files/s) 에 처리. external-api → calculator → synchronizer → cleanup 4개 Spring Boot 모듈이 Kafka 이벤트로 연쇄 동작하고, Airflow 가 제어한다.

이 규모에서 **"에러 로그 없이 0건 처리"** · **"매일 같은 시각에만 죽는 루프"** · **"버퍼 하나가 1GB heap 을 채운다"** 같은 문제가 실제로 발생했다. 본 케이스북은 그 문제들을 측정 기반으로 해결한 과정이다.

핵심 교훈: 성능 문제는 인프라 수가 아니라 **데이터 흐름·병목·관측성** 에서 발생한다. 숫자가 아니라 그 숫자가 나온 이유를 설명할 수 있어야 한다.

---

## 큐레이션 — 대표 사례 (impact-first)

| # | 무엇이 | 규모·영향 | 해결 | 상세 |
|---|--------|-----------|------|------|
| 1 | **루프가 매일 03:00 에만 죽음** | 273 iteration 정상 후 사망, ITEM_EQUIPMENT 지속 구동 안 됨 | OCID_LOOKUP refresh 창의 upstream null 을 defer/retry 로 흡수(사망 대신 대기) | [05-1](05_loop_lifecycle_observability.md) |
| 2 | **관측 로그가 사망 원인을 숨김** | 77K 라인 중 lifecycle 로그 0건 → 진단 불가 | log retention 30m→500m 증설로 사망 stacktrace 포착 | [05-3](05_loop_lifecycle_observability.md) |
| 3 | **에러 없이 데이터 100% 손실** | calculator/synchronizer 가 모든 chunk silent drop → valuation 0건 | chunk body dual-shape reader 신규 schema 인식 | [03-2](03_pipeline_data_correctness.md) |
| 4 | **600K IGN 처리 중 OOM, 2.5h run 사망** | 120MB string list 가 1GB heap 점유 | Channel streaming 으로 heap 120MB→~64KB | [02-1](02_memory_streaming.md) |
| 5 | **OOM 이 `catch(Exception)` 에 삼켜져 진짜 원인 소실** | sink writer silent 사망, 잘못된 에러 노출 | `Throwable` catch 로 Error 노출 + cause chain | [02-3](02_memory_streaming.md) |
| 6 | **비동기 체인 중 `.get()` 이 가상스레드 pinning** | worker-pool 고갈, 부하테스트 비결정적 저하 | `*Async` big-bang 마이그레이션 + CI grep gate 회귀 차단 | [01-1](01_async_concurrency.md) |
| 7 | **cleanup 이 ~200GB silent 미삭제** | `listByPrefix` 1000 keys truncation | continuationToken pagination | [06-2](06_infra_deploy_migration.md) |
| 8 | **매일 아침 두 스케줄러가 같은 slot 경쟁** | morning_chain DAG 매일 fail | legacy in-process cron 제거(단일 오케스트레이터) | [04-4](04_orchestration_airflow.md) |

---

## 주제별 전체 사례

| # | 파일 | 주제 | 사례 수 |
|---|------|------|--------|
| 01 | [01_async_concurrency.md](01_async_concurrency.md) | 비동기·동시성·가상스레드·락 | 6 |
| 02 | [02_memory_streaming.md](02_memory_streaming.md) | 메모리·OOM·스트리밍 I/O | 6 |
| 03 | [03_pipeline_data_correctness.md](03_pipeline_data_correctness.md) | 파이프라인 데이터 정합성(silent data loss) | 7 |
| 04 | [04_orchestration_airflow.md](04_orchestration_airflow.md) | 오케스트레이션·Airflow | 7 |
| 05 | [05_loop_lifecycle_observability.md](05_loop_lifecycle_observability.md) | 무한루프 생명주기·관측성 | 3 |
| 06 | [06_infra_deploy_migration.md](06_infra_deploy_migration.md) | 인프라·배포·마이그레이션 | 6 |

---

## 읽는 법 / 근거

각 사례 구조: **문제/에러**(증상 + 실측 메시지) → **원인** → **해결**(commit + ADR) → **왜 이 방법 / 대안**(기각안 포함).

근거 인용:
- `commit <sha>` — git history (모든 인용 commit 실존 검증됨)
- `ADR-XXX` — `docs/01_ADR/` (모든 인용 ADR 실존 검증됨)
- `Session 202606XX-XXXXXX` — `docs/ai-traces/` 세션 기록

기술적 깊이는 의도적이다 — 포트폴리오 독자에게 "디버깅을 얼마나 깊이 했는지" 가 credibility 의 핵심이기 때문. impact 는 상단 callout, 근거는 본문.

---

## 교차 교훈 (Cross-cutting lessons)

1. **관측성 회귀가 진짜 버그를 은폐한다** — 로그가 증거를 숨기면 "코드는 정상인데 죽는다" 가 된다. retention 먼저 의심(사례 05).
2. **`catch (Exception)` 이 `Error` 를 삼킨다** — OOM/StackOverflow 는 `Error`. 단일 writer/sink 코루틴은 `Throwable` 잡아야(사례 02-3).
3. **"에러 없이 0건" 이 가장 교활하다** — silent data loss. reader schema dual-shape 검증 필수(사례 03).
4. **control plane 이전 후 legacy 가 잔존하면 매일 fail** — 동일 cron/slot 중복. 퇴역은 `schedule=None`(사례 04).
5. **동기 bridge 가 비동기 체인을 깬다** — core port sync return → 모든 caller `.get()` pinning. `*Async` + CI grep gate(사례 01-1).
6. **측정 없는 최적화는 미신** — 이론상 완벽해도 실제 회귀. heap 이 effective gate 인 경우가 많다(사례 02-5).

---

*생성: 2026-06-28 · 소스: docs/ai-traces (110 sessions) + docs/01_ADR (171) + git log · fact 검증: commit 26/26·ADR 16/16 실존 확인*

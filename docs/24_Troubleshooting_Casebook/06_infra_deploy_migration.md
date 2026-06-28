# 06. 인프라·배포·마이그레이션

> nohup→docker 전환, MinIO 운영(network/SA/CI), Nexon HTTP pool 튜닝, orphan cleanup.
> 배포 모델 전환 중 드러난 network duality·권한·CI 드리프트.

**영향(Impact):** nohup→docker 전환 시 network duality 로 4 app 컨테이너 crash-loop; cleanup 의 `listByPrefix` truncation 으로 ~200GB silent 미삭제; 4 SA policy `DeleteObject` 누락으로 marker `AccessDenied`.

---

## 6-1. nohup→docker 전환 시 app crash-loop — infra DNS `SERVFAIL` + `:dev` 태그 부재

- **Session:** 20260626-013345-1258594 (및 20260626-065852)
- **문제/에러:** 4 모듈(external-api/calculator/synchronizer/cleanup)을 nohup→docker compose 전환 시 app 컨테이너가 `postgres`/`kafka`/`minio` DNS `SERVFAIL` 로 crash-loop. `services.yml` 기본 `maple/{module}:dev` 가 image not found.
- **원인:** network duality — infra(postgres/kafka/minio)는 `probabilistic-valuation-engine_maple-network`(project=probabilistic-valuation-engine), redis 만 `maple-network`. app `services.yml` 이 `maple-network` 선언 → infra service alias 미해석. grill(critic opus)+독자 검증으로 3 장애물(network duality / `:dev` 부재 / airflow host-network drift) 동시 발견.
- **해결:** (1) infra 컨테이너를 `docker network connect --alias <service> maple-network <ctn>` 로 live 연결(non-disruptive, `--alias` 필수 — 생략 시 service alias 미해석). (2) `deploy-apps.sh` 가 `:sha-*` 최신 태그 자동 해석(`:dev` 회피), `.env` `$` expansion 회피용 grep DB_URL. (3) airflow autoheal 은 host/bridge reconcile 선행 필요로 별도 이관(→ 사례 04-6). 검증: 4 모듈 `/actuator/health` 4/4 UP, ERROR 0, Kafka LAG 0, cadvisor 76 series. **ADR-737**, commits `b395132eb`/`ea2f42057` (#1428-1431/#1434).
- **왜 이 방법 / 대안:** live network connect 는 infra 무중단 reconcile(recreate 아님) — 영속성 부족(infra recreate 시 재연결)을 runbook 으로 보완. airflow 를 같이 전환 시 동작 중 webserver healthy 파손 위험 → 분리. `.env DB_ROOT_PASSWORD` 의 `$pNDA2` compose interpolation blank 를 postgres·app 동일 interpolation 으로 상호 일치(non-issue). **기각:** `:dev` 태그 강제 빌드(reproducibility ↓).

---

## 6-2. MinIO `listByPrefix` 1000 keys truncation — cleanup 이 ~200GB silent 미삭제

- **Session:** 20260615-010251-1114908
- **문제/에러:** `module-cleanup` `RunCleanupService` 가 object key 에서 runId 추출, but 70+ run × 수백 chunk 시 첫 2 runId 만 visible. cleanup "no runs to delete" 보고 — but ~200GB stale data 가 MinIO 잔존.
- **원인:** `listObjectsV2` 가 기본 1000 keys/page; 구현이 `continuationToken` 없이 1회 호출 → page 1 이후 silent drop.
- **해결:** `nextContinuationToken()` null 까지 loop, 기존 `deleteByPrefix` 의 pagination 패턴과 일치. commit `4002effd7` (#1285). 검증: `POST /api/internal/cleanup/runs` 가 `runsDeleted=5, bytesDeleted=14,151,671,872`(~14GB) 보고(이전 0).
- **왜 이 방법 / 대안:** 기존 `deleteByPrefix` pagination 재사용(고수준 SDK paginator 도입 대신 일관성). ADR 없음(bugfix 수준).

---

## 6-3. MinIO 4 SA policy 에 `DeleteObject` 누락 — `_RUNNING`/`_SUCCESS` marker `AccessDenied`

- **Session:** 20260615-010251-1114908 (+ 0615-074305, -165301)
- **문제/에러:** cleanup 이 `_RUNNING`/`_SUCCESS` lifecycle marker delete 시 `AccessDenied`. audit 결과 4 service-account prefix-policy 격리(ext-api/cleanup/calculator+read-api)가 `s3:ListBucket`/`s3:PutObject` 부여 but marker 관리에 필요한 `s3:DeleteObject` 누락.
- **원인:** per-SA prefix-policy 가 lifecycle marker 도입 *전* 정의; marker delete 권한이 policy set 에 누락.
- **해결:** 4 SA policy audit + `runs/_RUNNING`/`_SUCCESS`/`calculator/runs/*` prefix 에 `s3:DeleteObject` 추가. commit `2e779d0c5`. 4-SA ephemeral-CI 격리 설계(#1288, `f09fc9d03`) 연동.
- **왜 이 방법 / 대안:** SA 는 runtime-scoped("영구 보관 이유 无") → per-prefix least-privilege + ephemeral CI key. **기격:** single privileged cleanup SA(격리 목적 위반).

---

## 6-4. Nexon HTTP pool / rate-limiter mis-tune — fetch ~215/s cap (300/s 미도달)

- **Session:** 20260617-053345-4091260 (+ 20260616-094101)
- **문제/에러:** fetch 병목 — wave 가 직렬로 돌아 batch duration 2배. connection pending 압력; 300/s rate 미도달.
- **원인:** pool(150) + in-flight(100) cap 이 250/s rate limiter throttle; in-container `minio-bootstrap` MINIO_ENDPOINT misconfig; rate-limiter non-greedy refill.
- **해결:** `NEXON_HTTP_MAX_CONNECTIONS` 150→250(`a260709e9`), rate limiter greedy refill + pool tune(`998c0bf60`), in-container `minio-bootstrap` MINIO_ENDPOINT override(`939121ac4`, #1298). ADR-729.
- **왜 이 방법 / 대안:** ADR-717 이 pool 150/rate 250 를 안정 band 로 측정; 06-17 발견("throughput 영향 無 — Heap이 effective gate")이 heap bump(1g→2g, #1293) 후 다음 gate 가 pool 임을 보여 150→250 정당화. **기각:** pool 500(과잉 provisioning — pending 압력 재발, 비례 throughput gain 無). greedy refill 을 tail-latency fairness 축소와 burst throughput 지속 교환.

---

## 6-5. MinIO CI integration hard-fail (2026-06-19 이후 매 run) — service container 건강 미달

- **Session:** 20260622-021100-3692459, 20260622-050451-4148311
- **문제/에러:** `minio-it` CI job 이 2026-06-19 이후 매 run "Initialize containers" fail. 중첩: (a) `minio/minio:latest` 가 default CMD 미탑재 → 인수 없으면 help print 후 exit; (b) GH Actions service-container 가 image ENTRYPOINT/CMD override 불가; (c) job-level `env:` 가 `steps.*` context 참조(job-level 금지 — secrets/vars/github/inputs/matrix/needs/strategy 만 허용) → `workflow file issue`(0s run); (d) `mc admin user list --json` 이 구 grep 이 기대한 `accessKey` field 미노출, `mc admin user info` root 에 `Access Denied`.
- **원인:** image/CLI 동작 drift + GH Actions context 규칙. 각 fix 가 다음 bug 노출로 중첩.
- **해결:** (2026-06-22 commits) `0110f7453` MinIO 를 detached background container(service 아님)로 explicit `server /data --console-address :9001` launch, liveness endpoint 200 까지 poll; `8d86a5412` invalid `steps.*` ref 제거; `c512beb20` `minio/mc` entrypoint override(`/bin/sh`); `313c93a4e` SA 를 `mc admin user list` 대신 `mc stat local/maple-expectation`(bucket visible) 로 verify.
- **왜 이 방법 / 대안:** bucket-stat check 를 `mc` JSON schema 역추적 대신 — Run-IT step 이 SA credential 직접 사용 = authoritative correctness signal; bucket 존재가 충분 전제. service container 는 GH Actions 가 entrypoint override 불가로 폐기.

---

## 6-6. orphan temp-file leak — ext-api snapshot path 에 cleanup hook 부재

- **Session:** 20260619-061917-2513672, 20260619-075233-2762017
- **문제/에러:** `ChunkFileManager` 가 chunk sink 실패/restart 후 orphan temp file 잔존 → disk leak.
- **원인:** temp file sweep lifecycle hook 부재; 실패 시 partial file 잔존.
- **해결:** `OrphanTempFileCleanupHook` 도입(commit `a38e6a3d3`) — **per-file fail-soft delete**(한 bad file 이 sweep 중단시키지 않음); `runWithDeadline` timeout path WARN, 실패 시 ERROR(`55e467713`); INFO summary log `scanned/deleted/bytes_freed/failed`. commit `66f16f9ed` `loopExecutor` 전환(`TaskExecutionAutoConfiguration` 가 custom `Executor` bean 존재 시 suppress).
- **왜 이 방법 / 대안:** fail-soft 로 cleanup 자체가 실패 source 가 아닌 self-healing 되게; deadline + level-differentiated logging 으로 timeout 이 data error 로 위장 방지.

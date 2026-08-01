# 증거 원장

## 사용 규칙

- 최종 이력서와 포트폴리오의 핵심 문장은 아래 ID에 연결한다. 문서/PR 제목만으로 완료를 단정하지 않고, 실제 diff·현재 또는 해당 ref의 코드·측정 조건을 함께 본다.
- `T1`은 코드·실제 diff·원시 또는 재현 가능한 측정, `T2`는 조건이 적힌 세부 보고서, `T3`은 ADR/설계 문서, `T4`는 PR·이슈·AI 기록·기존 자기서술이다.
- 개인 기여는 기존 이력서에 공개된 GitHub 계정 `zbnerd`, PR/커밋의 author, 1인 백엔드 프로젝트라는 자료를 근거로 한다. AI 기록은 도구 활용의 증거이지, 그 안의 자기평가나 숫자를 사실로 승인하는 자료가 아니다.
- “결과”는 프로젝트 관측치, “내 기여”는 문제 정의·설계·구현·검증·의사결정 범위로 분리해 쓴다.

## 채택 주장

### E-001 — 지원자·프로젝트 기본 정보

- 최종 주장: 이승준, 신입 백엔드 지원자. GitHub `zbnerd`, 공개 이메일/블로그, 교육·비개발 경력·자격 정보는 기존 이력서와 동일하게 유지한다.
- 근거: `docs/Portfolio_Book/이력서.pdf` 2쪽 전체(T1, 사용자가 제공한 원본).
- 내 기여/범위: 사실 정보의 소유자는 지원자. 내용 변경 없이 재배치만 했다.
- 한계: 희망 회사·직무 세부, 영어명, 전화번호, 주소는 원본에 없으므로 만들지 않았다.
- 상태: 채택.

### E-002 — PGMQ 중심 처리에서 Kafka claim-check ETL로 전환

- 최종 주장: 대용량 본문은 gzip JSONL 청크로 object storage에 두고 Kafka에는 위치·건수·크기 메타데이터를 전달하는 external-api → calculator → synchronizer 파이프라인을 설계·구현했으며, cleanup은 이후 standalone 단계로 분리했다.
- 코드/문서: `README.md`; `docs/architecture.md`; `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt:13`; `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:59`; `module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt:12`; `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`.
- Git/GitHub: PR [#792](https://github.com/zbnerd/probabilistic-valuation-engine/pull/792), [#807](https://github.com/zbnerd/probabilistic-valuation-engine/pull/807), [#808](https://github.com/zbnerd/probabilistic-valuation-engine/pull/808), [#817](https://github.com/zbnerd/probabilistic-valuation-engine/pull/817); 모두 GitHub merge metadata와 로컬 diff 확인(T1/T4).
- 내 기여: 개인 프로젝트에서 경계 설계, 청크 형식·백프레셔·이벤트 계약 구현, 장기 검증 기준 수립 및 결과 판정.
- 한계: Kafka “도입” 자체보다 payload/metadata 경계와 재처리 안전성을 성과로 표현한다. 2026-05-23~27의 82시간 실행은 3서비스이며 cleanup은 external-api 내부 scheduler였다. standalone `module-cleanup`의 첫 commit은 2026-06-07이므로 현재 4단계 구조와 당시 결과를 같은 시점으로 합치지 않는다. 브로커 HA를 실증했다는 주장도 하지 않는다.
- 상태: 채택.

### E-003 — upload 완료 뒤 이벤트 발행과 bounded backpressure

- 최종 주장: bounded queue와 단일 writer로 메모리를 제한하고, 비동기 object upload가 성공한 뒤에만 chunk-ready 이벤트를 발행하도록 순서를 고정했다.
- 코드: `ChunkedSnapshotSink.kt:18-24`, `:42-65`, `:194-223`, `:307-344`; `CalculationResultWriter.kt:52-73`, `:75-145`(T1).
- Git/GitHub: PR [#1283](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1283), [#1294](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1294), [#1307](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1307).
- 내 기여: blocking upload/close와 publish-before-visible race를 분리해 callback 순서를 설계하고 실패 시 publish를 건너뛰는 경계를 구현.
- 한계: 주석 속 과거 지연 숫자는 별도 원시 측정 없이는 최종 성과 수치로 쓰지 않았다. upload-success callback 관련 PR #1283/#1294/#1307은 2026-06월 변경으로 2026-05월 82시간 run보다 뒤다. 따라서 82시간 수치를 현 callback 경계의 장기 회귀 증거로 사용하지 않는다.
- 상태: 채택.

### E-004 — at-least-once 소비의 멱등·재처리 경계

- 최종 주장: 결정적 결과 object key, 결과 존재 시 재계산 대신 이벤트 재발행, 성공 처리 뒤 수동 ACK, read-model `ON CONFLICT` upsert로 중복 전달을 안전하게 흡수했다.
- 코드: `CalculatorChunkProcessingCoordinator.kt:67-99`, `:132-179`; `SnapshotDispatchService.kt:28-70`; `EquipmentReadModelRepository.kt:17-41`(T1).
- 내 기여: “한 번만 전달”을 가정하지 않고 artifact 존재·ACK·DB 투영 각각에 재처리 규칙을 둠.
- 한계: 로컬 HEAD의 cleanup inbox는 메모리 queue이며 overflow 시 oldest drop(`ConsumedChunkInbox.kt:13-61`)이다. 따라서 이 HEAD만 근거로 cleanup까지 end-to-end exactly-once 또는 durable하다고 쓰지 않는다. 2026-07-20 PR #1463의 durable inbox는 별도 최신 ref 성과로 구분한다.
- 상태: 채택(정확히 at-least-once + idempotent 처리로 표현).

### E-004A — pipe streaming data-loss race를 temp-file async upload로 교체

- 최종 주장: calculator의 `PipedInputStream`/`PipedOutputStream` + SDK background reader가 경쟁해 0-byte/truncated gzip을 만들던 설계를 폐기하고, gzip temp file을 완전히 닫은 뒤 `putFileAsync`로 업로드하도록 교체했다.
- 코드/결정: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt:52-145`; Accepted `docs/01_ADR/ADR-730_calculator-writer-temp-file-upload.md:9-66`(T1/T3).
- Git/GitHub: 실패 설계 commit `85b5528df557377675b080edfe7d6515c9993461`(2026-06-20) → corrective commit `205ce14b814b98cf1533a8072d1393dca9cfd310`과 merged PR [#1325](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1325)(2026-06-22).
- 내 기여: “중간 disk I/O가 없는 streaming” 선택이 SDK reader lifecycle과 맞지 않음을 재현하고, throughput보다 artifact correctness를 우선해 disk-backed staging으로 의사결정·구현.
- 검증 결과: 2026-06-22 MinIO E2E, internal ITEM_EQUIPMENT trigger(`runId=verify-writer-fix-1`). before는 processed chunks 0, 이전 artifact 0 bytes, 9분간 `Read end dead` 약 14,921건. after는 processed 64 chunks, failed 0, calculated 1,948,957 items, 해당 오류 0, 다운로드한 gzip 674,986 bytes/30,507 valid JSONL rows(`ADR-730:70-91`).
- 한계: raw log bundle은 checkout에 없고 수치가 ADR에 보존돼 있다. temp file은 chunk당 최대 500 records/128 MiB uncompressed의 disk capacity/I/O 비용을 만든다. 처리량 배수가 아니라 **데이터 정합성 회복**으로 표현한다.
- 상태: 강한 제한부 채택.

### E-005 — 82시간 장기 실행 관측

- 최종 주장: 2026-05-23 22:53~05-27 09:08, external-api/calculator/synchronizer 3개 프로세스, 모듈별 `-Xms512m -Xmx1g` 조건에서 82시간 15분 동안 재시작 0, ERROR 로그 0, 60,190,417 users, 4,034,907,241 items, 120,442 chunks(실패 0), input 13.31 TB(uncompressed)를 기록했다. 48~82시간 RSS는 3,546~3,687 MB(<4% drift)였다.
- 근거: `docs/endurance-test/endurance-report-82h.md:3-6`, `:18-28`, `:43-49`, `:65-81`, `:96-102`, `:124-152`(T2); 문서를 추가한 merged PR [#853](https://github.com/zbnerd/probabilistic-valuation-engine/pull/853)(T4).
- 내 기여: 장기 테스트 운영, counter/log/RSS/disk 관측, cleanup 균형 및 실패 기준 판정.
- 한계: 보고서 기반이며 원시 Prometheus snapshot/log 전체는 저장소에서 확인하지 못했다. 3서비스·당시 host-process 배포의 시점 한정 결과이며 cleanup은 external-api 내부 `ConsumedChunkCleanupScheduler`였다. 이후 standalone cleanup/4서비스 Docker 환경에 그대로 일반화하지 않는다.
- 상태: 제한부 채택.

### E-006 — 보고서상 약 71시간 관측에서 이중 오케스트레이션 race 발견

- 최종 주장: 보고서는 2026-06-23~26 실행을 약 71시간으로 표기하지만, 기재 시각 06-23 09:03~06-26 05:30은 68시간 27분이다. 이 관측 창에서 2.28B items/계산 오류 0, 인프라 재시작·OOM·Kafka/DB 장애 0을 관측했지만 03:00 처리 중단이 두 번 발생해 수동 loop restart가 필요했다. 06-26 중단은 Airflow DAG와 legacy `@Scheduled`의 동일 phase-slot 점유를 로그 타임라인으로 직접 확인했고, 06-25 중단은 보고서가 같은 dual-orchestration 원인의 다른 발현으로 소급 추정한다.
- 근거: `docs/endurance-test/endurance-report-71h.md:3-18`, `:22-47`, `:61-90`, `:134-143`, `:160-182`(T2); `docs/01_ADR/ADR-736_disable-legacy-daily-cron.md:13-34`(T3).
- 내 기여: 장기 시계열·로그·코드를 연결해 06-26의 동일 phase-slot 점유를 직접 확인하고, 06-25의 최초 설명(null upstream)과 구분해 확정 증거와 소급 추정을 분리함.
- 한계: 150시간 목표와 “무개입 연속 운영” 기준은 미달이다. 보고서 라벨과 타임스탬프의 2시간33분 차이도 함께 명시한다.
- 상태: 제한부 채택.

### E-007 — legacy cron 제거와 검증 경계

- 최종 주장: 03:00 실행 주체를 Airflow `morning_chain` 하나로 단일화하도록 legacy in-process cron을 제거했고, compile/기존 scheduler test를 통과했다.
- 근거: Accepted ADR-736 `:38-50`, `:78-90`; merged PR [#1433](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1433)(T1/T3/T4).
- 내 기여: 중복 실행을 fallback으로 남기는 선택과 Airflow SPOF를 수용하는 선택을 비교하고 control plane 단일화를 결정·구현.
- 한계: ADR 자체가 다음 03:00 런타임 검증을 deferred로 기록한다. 연속 일일 발화 성공이 후속 보고서로 입증되기 전에는 “재발 0”으로 쓰지 않는다.
- 상태: 제한부 채택.

### E-008 — 보고서상 약 80시간 관측 창에서 single-writer 병목 식별

- 최종 주장: 2026-06-29~07-02 ITEM_EQUIPMENT phase의 보고서상 약 80시간 관측 창은 Contabo 8 cores/23 GB/600 Mbps, 4 서비스 컨테이너 조건이다. 서비스별 uptime은 external-api/calculator 80h14m, synchronizer/cleanup 71h11m으로 다르다. 이 창에서 38.08M users·2.72B items, sustained 100~150 users/s를 관측했고, burst에서 queue 3,000이 차고 submit이 1.3~1.7초로 늘었으며 35초간 queue depth가 약 2,475 줄어 **순감소율 약 71 records/s**로 계산해 single-consumer writer queue 경계를 병목으로 식별했다.
- 근거: `ENDURANCE_THROUGHPUT_CEILING_20260702.md:3-18`, `:20-31`, `:35-58`, `:130-178`, `:192-204`(T2); merged report PR [#1451](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1451)(T4).
- 내 기여: CPU·메모리·네트워크·Nexon 실패·GC와 queue/submit 시계열을 비교해 병목 후보를 writer drain으로 좁힘.
- 한계: 진단 실행이며 코드 변경을 적용하지 않은 관측이다. 서비스별 uptime이 다르고 같은 창의 before/after 개선률도 없다. 보고서의 `lifetime avg 136.57`은 38.08M/80h14m 단순 산술값 약 131.84와 불일치해 최종 성과에서 제외한다. Airflow scheduler는 password drift로 359회 restart한 별도 incident가 있어 “전체 인프라 무장애”로 쓰지 않는다.
- 상태: 채택.

### E-009 — writer hot path의 실제 변경 시점과 미검증 후속 변경

- 최종 주장: 80시간 보고서 commit `bd952d5a7`의 ancestry에는 producer-side Jackson 직렬화/OCID read-through commit `b733a8dfb`(2026-06-20)와 upload-success callback commit `ecee74549`(2026-06-16)가 이미 포함돼 있었다. 관측 시 writer는 pre-serialized record를 한 consumer가 gzip append/chunk close하고 async upload를 등록하는 구조였다. gzip `BEST_SPEED` commit `7c120d647`은 보고서 commit 뒤 2026-07-02에 적용됐다.
- 근거: `git show bd952d5a7:module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`; 위 세 commit의 ancestry·author date; 현재 `ChunkedSnapshotSink.kt:68-75`, `:234-305`; ADR-729 `:32-81`; merged PR [#1321](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1321), [#1453](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1453)(T1/T3/T4).
- 내 기여: 기존 hot-path 구성을 포함한 실제 관측 ref를 보고서와 대조해, 병목을 “직렬화·동기 PUT 전체”가 아니라 단일 consumer의 gzip/chunk-close·upload 등록 queue 경계로 좁힘. 후속 compression-level 변경은 별도 검증 대상으로 분리.
- 한계: ADR-729는 `Status: Proposed`, `Observed Result: TBD`이고 98→≥150은 목표다. PR #1452(Kafka batching/MinIO tuning)는 closed-unmerged이며 `BEST_SPEED` 뒤 동일 조건 after가 없다. producer serialization/async callback을 80시간 진단의 사후 개선 또는 개선률 근거로 표현하지 않는다.
- 상태: 시점이 확인된 구현·진단만 채택; 성능 향상 수치는 보류.

### E-010 — ETL runtime/module ownership 심화

- 최종 주장: 2026-07-20 merged PR #1463 final tip `1f47173e3`에서 artifact/Kafka 구현 모듈, `module-core`의 pure valuation kernel, Nexon client와 worker-owned runtime 경계를 분리하고 active worker의 `module-infra` 직접 runtime dependency를 3개→0개로 줄인 상태를 확인했다. cleanup worker는 inbox subscription/processing을, `module-pipeline-artifact`는 durable inbox store를 소유한다. focused test 86개 통과와 4 worker bootJar 빌드는 tip 직전 closure-evidence commit `11ee3c727` 기준이다.
- 근거: ref `refactor/etl-infra-deepening` final tip `1f47173e3`의 source/runtime dependency graph(T1); `docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md:111-170`과 commit `11ee3c727`(T1/T2); merged PR [#1463](https://github.com/zbnerd/probabilistic-valuation-engine/pull/1463)(T4).
- 내 기여: 공용 `module-infra`의 우연한 의존성을 worker-owned runtime resource와 전용 artifact/messaging 계약으로 재배치하고 dependency guard를 build gate로 고정.
- 한계: 이 변경은 로컬 HEAD `4da39850b`의 후속 42개 branch commit이며 GitHub상 develop에 merge된 상태다. closure evidence 뒤 final tip commit에서 production subscription class 4개와 test file 2개가 변경됐으며, 이 마지막 수정을 포함한 focused-test/bootJar 재실행 증거는 없다. 보고서는 root/full check, Testcontainers, load/performance run도 생략했다(`:200-206`). 성능 개선으로 표현하지 않는다.
- 상태: 채택(시점/ref·검증 범위 명시).

### E-011 — 과거 read-path 실데이터 벤치마크

- 최종 주장: 2026-03-24, 문서상 200k~300k DB rows에서 `wrk -t4 -c200 -d120s`로 7,347 RPS, p99 36 ms, errors 0을 후속 측정했다.
- 근거: `docs/06_Performance_Journey/10_real_data_challenge.md:91-111`(T2보다 낮은 T2-, 원시 wrk 결과 파일 부재); `09_postgresql_notify.md:115-190`은 최초 7,347 측정의 errors 65와 후속 측정들을 별도로 기록.
- 내 기여: cache fast path, single-flight, batch write/read-model 전략을 실데이터 조건에서 재확인하려 한 성능 검증.
- 한계: 저장소에 원시 wrk stdout/환경 전체가 없고, 문서 내부에 최초 65 errors와 후속 0 errors가 공존한다. `97 RPS`와는 도구·connection·DB 상태·워크로드가 달라 `76배 개선`으로 쓰지 않는다.
- 상태: 이력서에는 “문서상 후속 측정”으로 제한부 채택; 포트폴리오 핵심 5개 사례에서는 제외.

### E-011A — cache fast path·Single-Flight·batch write 구현

- 최종 주장: 조회 cache hit/miss 분리, 동일 key miss의 Single-Flight, write-behind, batch UPSERT는 서로 다른 진화 단계로 구현됐다. 계층형 캐시는 PR #83, V4 Single-Flight는 #263, write-behind buffer는 #266, production batch upsert/micro-batching은 #618의 merged diff로 각각 확인한다. 현재 `TieredCache`는 leader/follower 경계를, `BatchL2WriteBuffer`는 10 ms window·최대 500 entries의 deduplicate write를 보존한다.
- 코드/GitHub: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt:72-92`, `:319-367`, `:400-420`; `cache/tiered/BatchL2WriteBuffer.kt:13-42`, `:61-135`; `persistence/repository/ExpectationBatchRepository.kt:13-58`, `:75-103`; merged PR [#83](https://github.com/zbnerd/probabilistic-valuation-engine/pull/83)(계층형 cache, merge `c9fa7032b`, 3 files/+49/-15), [#263](https://github.com/zbnerd/probabilistic-valuation-engine/pull/263)(V4 Single-Flight, merge `418cc04df`), [#266](https://github.com/zbnerd/probabilistic-valuation-engine/pull/266)(write-behind, merge `db7f3f99d`), [#618](https://github.com/zbnerd/probabilistic-valuation-engine/pull/618)(batch upsert/micro-batching, merge `0264e5f77`)(T1/T4).
- 내 기여: 반복 조회의 cache fast path, 동시 miss 합류, 비동기 write와 대량 batch의 경계를 구현·진화시킴.
- 한계: 네 PR과 현재 Kotlin 구현은 진화 시점·코드 세대가 다르다. 이를 하나의 동일-condition benchmark 원인으로 합치지 않으며, “중복 호출 합류/DB write batching”은 구조적 동작 설명이지 측정된 감소율이 아니다. 7,347 RPS의 제한은 E-011에서 별도로 관리한다.
- 상태: 구현 주장 채택; 정량 개선률은 보류.

### E-012 — Resilience4j 오픈소스 기여

- 최종 주장: Resilience4j Kotlin `TimeLimiter`에 일반 함수용 `decorateFunction`/`executeFunction` API와 5개 시나리오 테스트를 추가한 PR #2407이 2026-03-06 merge됐다.
- 근거: upstream issue [#2307](https://github.com/resilience4j/resilience4j/issues/2307), merged PR [#2407](https://github.com/resilience4j/resilience4j/pull/2407); GitHub API 기준 author `zbnerd`, 2 files, +163/-0, main API +28, test +135, merge time 2026-03-06T14:52:15Z(T1/T4).
- 내 기여: 누락된 synchronous Kotlin API의 일관성을 확인하고 구현·테스트를 제출.
- 한계: upstream issue의 최초 문제 제기자는 다른 사용자다. “문제를 최초 발견했다”가 아니라 “공개 이슈를 구현해 해결했다”로 쓴다.
- 상태: 채택.

### E-013 — AI 보조 개발의 개인 기여 표현

- 최종 주장: AI로 탐색·초안·반대 관점을 병렬 수집하되, patch·테스트·로그·DB/Prometheus 지표로 검증하고 최종 의사결정과 acceptance를 직접 수행했다.
- 근거: `docs/ai-traces` 경로 불일치 조사 및 `ai_traces_summary.md`; 실제 corpus 166 sessions/882 files/5,977,278 logical bytes, 358 gzip 전부 valid, JSONL 454개 중 1개 부분 손상; commit/PR author와 실제 diff; 원본 이력서의 기존 자기소개(T1/T4).
- 내 기여: 문제 선택, 위험/검증 기준, destructive action 금지, 결과 해석과 채택/기각.
- 한계: AI 세션의 자기평가·“완료” 문구는 사실 근거로 쓰지 않는다. 특정 코드 줄을 사람이 직접 타이핑했다는 의미의 단독 저작 표현도 피한다.
- 상태: 채택.

## 전수성 증거

### E-014 — Git commit 전수 조사

- 범위: `git rev-list --all` 고유 2,342개(2025-07-30~2026-07-20), merge 377, revert 7.
- 산출물: `commit_inventory.csv`.
- 검증: CSV hash set과 Git hash set의 완전 일치, 통계·파일·요약을 동일 parent policy로 대조, UTF-8/RFC4180/PII·credential/formula 안전성 확인.
- 상태: 최종 독립 재검증 결과를 인벤토리와 함께 사용.

### E-015 — GitHub PR/이슈 전수 조사

- 범위: PR 709/709 unique, issue 752/752 unique. REST pagination, Search total, GraphQL total이 각각 일치.
- 산출물: `pr_inventory.md`, `issue_inventory.md`.
- 제한: 마지막 209개 PR의 상세 GraphQL 요청이 반복 HTTP 502여서 review/discussion/commit/formal link/file metadata는 inaccessible로 명시했다. PR 번호·상태·merge metadata는 REST로 보존했다. 핵심 PR은 로컬 diff 또는 개별 GitHub API로 별도 교차검증했다.

### E-016 — 원본 PDF 전수 조사

- 범위: 작성 가이드 31쪽, 이력서 2쪽, 포트폴리오 1쪽. PyMuPDF/pypdf 구조·텍스트 확인 후 2배율로 34쪽 전부 시각 검사.
- 결과: 암호화/AcroForm 없음. 원본을 변경하지 않고 별도 완성본 생성.
- SHA-256: 가이드 `e67b7478…a59a7b`, 이력서 `050ebd6d…3db2e`, 포트폴리오 `fb2104e6…dba7b`.

## 기각·보류 주장

| ID | 주장 | 판정 | 이유 |
|---|---|---|---|
| R-001 | 97→7,347 RPS, 76배 향상 | 기각 | 동일 실험이 아니며 도구·connection·DB rows·cache/workload 조건이 다름 |
| R-002 | 150시간 완전 무인 안정성 달성 | 기각 | 보고서상 71시간(기재 시각 68h27m)에 종료, orchestration bug 2회와 수동 restart 개입 기록 |
| R-003 | ADR-736 이후 slot race 재발 0 | 보류 | 코드/테스트 통과, 다음 03:00 runtime validation deferred |
| R-004 | producer serialization로 ≥150 files/s 달성 | 보류 | ADR-729 `Observed Result: TBD`; 목표와 관측을 분리해야 함 |
| R-005 | flush-time rollup으로 파일 10~100배 감소 | 기각(성과) | ADR-743 `Proposed`, implementation out of scope; 추정치 |
| R-006 | PR #1452의 Kafka/MinIO tuning 적용 | 기각 | GitHub상 closed-unmerged |
| R-007 | end-to-end exactly-once | 기각 | Kafka/ACK/upsert는 at-least-once + idempotent 경계이며 로컬 HEAD cleanup inbox는 in-memory/drop-oldest |
| R-008 | PR/이슈 closed = 해결/배포 | 기각 | merged PR/actual diff가 없는 closed issue는 해결 미검증 |
| R-009 | 선형 scale-out 또는 환산 330K RPS | 기각 | 실측이 아닌 추정/응답 크기 환산 |
| R-010 | 80시간 전체 인프라 무장애 | 기각 | Airflow scheduler password drift로 359 restarts 기록 |
| R-011 | 운영 write-behind와 backfill이 현재도 완전히 분리됨 | 기각 | 과거 문서의 분리 서술과 달리 현재 `BulkLoaderService`가 동일 `ExpectationWriteBackBuffer`의 pending count로 backpressure를 제어함 |
| R-012 | PR #1463 final tip까지 86 focused tests·4 bootJar로 검증됨 | 기각 | 해당 evidence run은 tip 직전 `11ee3c727` 기준이며 final `1f47173e3`의 subscription-constructor 수정을 포함한 rerun은 없음 |
| R-013 | 80h 보고서의 `lifetime avg 136.57 users/s` | 기각 | 38.08M users/80h14m의 단순 산술값은 약 131.84로 불일치하며 raw Prometheus query/window가 없음 |

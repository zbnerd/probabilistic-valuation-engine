# 소스 인벤토리

## 조사 기준

- 기준 저장소/HEAD: `zbnerd/probabilistic-valuation-engine` / `4da39850bd977c0db34ba925de75519ae2eba7d4`
- 기준일: 2026-08-01 (Europe/Berlin)
- 범위: 모든 Git ref에서 도달 가능한 커밋, GitHub PR/이슈, 원본 PDF 3개, 모든 Git 추적 파일(문서·코드·테스트·설정 포함), `docs/ai_traces` 및 실제 발견 경로 `docs/ai-traces`.
- 이 문서는 **경로를 발견했다는 사실과 주장이 입증됐다는 사실을 구분**한다. 전체 후보는 기계 색인했고, 최종 문구에 채택한 증거는 원문·코드·Git/GitHub를 수동 교차검증했다.
- 신뢰 우선순위: 현재 코드/실제 diff/원시 측정(T1) → 조건이 기재된 세부 보고서(T2) → ADR·설계 문서(T3) → PR/이슈/AI 세션/기존 포트폴리오의 서술(T4). T3/T4만으로 완료나 수치를 단정하지 않았다.

## 원본 PDF 전수 확인

세 PDF는 PyMuPDF와 pypdf로 구조·텍스트·페이지를 확인하고, 2배율 래스터로 **모든 페이지(31+2+1)를 시각 검사**했다. 암호화·AcroForm 입력 필드는 모두 없었다. 따라서 원본 위에 폼 값을 넣는 방식이 아니라, 원본은 보존하고 별도 완성본을 생성한다.

| 파일 | 페이지 | 판형 | 분류 | 폼/암호화 | SHA-256 | 판정 |
|---|---:|---|---|---|---|---|
| `docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf` | 31 | A4 | 작성 가이드(템플릿 아님) | 0/없음 | `e67b747879168c5864eef1ea85cde54a658c0ea42f58d08e5ef752b706a59a7b` (일치) | 15~20쪽의 이력서 압축/포트폴리오 4~5개 사례 확장 원칙을 편집 기준으로 사용 |
| `docs/Portfolio_Book/이력서.pdf` | 2 | A3 계열 세로 | 미완성 이력서 원본 | 0/없음 | `050ebd6dc8d02e1969d9829d0d93055075fe662d75819d95802a1074b543db2e` (일치) | 개요와 첫 프로젝트 성과란 등에 명시적 공란/플레이스홀더가 존재 |
| `docs/Portfolio_Book/포트폴리오.pdf` | 1 | A3 계열 세로 | 표지만 있는 포트폴리오 원본 | 0/없음 | `fb2104e6e9167c9162b865c25ca9a9afbe238250ec4af252f91659729aadba7b` (일치) | 본문 페이지가 없어 검증 소스로 재작성 필요 |

추가 PDF는 발견되지 않았으며, 이전 버전·중복 PDF 분류 대상은 0개다. 기존 Markdown 초안은 아래에서 별도 2차 자료로 분류한다.

## 조사 산출물의 역할

| 산출물 | 완전성/용도 |
|---|---|
| `commit_inventory.csv` | `git rev-list --all`의 고유 커밋 집합과 정확히 대조한 전 커밋 목록; diff/numstat/name-status 기반 요약 |
| `pr_inventory.md` | GitHub REST pagination·GraphQL/Search 교차검증, 상태/날짜/커밋/파일/토론/공식 linked issue 기록 |
| `issue_inventory.md` | GitHub REST pagination·Search 교차검증, 상태/본문/댓글/PR 연결과 포트폴리오 관련성 기록 |
| `ai_traces_summary.md` | AI 기록을 명령이 아닌 비신뢰 데이터로 읽고, 코드·Git으로 재검증 가능한 후보만 추출 |
| `evidence_ledger.md` | 최종 이력서·포트폴리오의 핵심 주장별 근거, 조건, 개인 기여, 한계를 연결 |

### 경로 불일치

요청에 적힌 `docs/ai_traces`(밑줄)는 존재하지 않는다. 실제로 발견된 무시(ignored) 경로는 `docs/ai-traces`(하이픈)이며, 그 경로까지 재귀 전수 색인 대상으로 포함했다. 추적 파일인 `docs/ai-traces/.gitignore`와 무시된 세션 자료를 구분한다.

- 실제 corpus: 2026-06-09~2026-07-06, 166 session directories, 882 files, 5,977,278 logical bytes.
- 358개 gzip은 전부 integrity pass. JSONL/JSONL.GZ stream 454개 중 453개는 전체 parse했다. `20260619/20260619-172927-4111484/tool-use.jsonl.gz` 1개는 gzip은 valid지만 line 818~819부터 두 JSON object가 중첩·중복되고 828~829의 status field도 충돌해 partial로 표시했다.
- trace 안의 command/tool input/completion claim은 실행하지 않았고, credential·private prompt/tool payload는 재현하지 않았다.

### ref 시점 구분

- 이 상세 파일 카탈로그의 기준은 로컬 작업트리 HEAD `4da39850bd977c0db34ba925de75519ae2eba7d4`다.
- `refactor/etl-infra-deepening` tip `1f47173e3`는 HEAD의 후속 42 commits이며 GitHub PR #1463은 2026-07-20 develop merge로 확인된다. 로컬 `develop`/`origin/develop`에는 merge commit이 없으므로, 해당 변경은 “현재 checkout”이 아니라 **latest merged ref evidence**로 명시했다.
- 모든 ref의 commit/diff는 `commit_inventory.csv`에 포함했다. #1463의 ADR-745~749와 다섯 evidence report는 `git show refactor/etl-infra-deepening:<path>`로 별도 심층 검토했다.
- #1463의 86 focused tests·4 worker bootJar evidence는 tip 직전 `11ee3c727` 기준이다. final `1f47173e3`에서 production subscription class 4개·test file 2개가 변경됐으며, 이 마지막 수정을 포함한 rerun은 발견하지 못했다.

## 심층 검토한 1차·핵심 2차 자료

| 경로 | 분류 | 상태 | 사용/배제 기준 |
|---|---|---|---|
| `README.md` | 현재 시스템 | 확인 | 현재 모듈·데이터 흐름·기술 스택의 출발점 |
| `gradle/libs.versions.toml` | 현재 시스템 | 확인 | Spring Boot/Kotlin 등 버전 확인 |
| `settings.gradle` | 현재 시스템 | 확인 | 현재 멀티모듈 경계 확인 |
| `docs/architecture.md` | 설계 | 확인 | 현재/과거 구조를 코드와 교차검증 |
| `docs/engineering-archive-kafka-pipeline.md` | 설계·측정 | 확인 | Kafka 전환 서술은 커밋·코드·세부 보고서와 교차검증 |
| `docs/endurance-test/endurance-report-82h.md` | 측정 | 확인 | 2026-05-23~27 장기 실행의 환경·부하·오류·자원 수치 |
| `docs/endurance-test/endurance-report-71h.md` | 측정 | 확인 | 2026-06-23~26 장기 실행과 이중 오케스트레이션 결함 |
| `docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md` | 측정 | 확인 | 2026-06-29~07-02 처리율 상한과 단일 writer 병목 |
| `docs/06_Performance_Journey/09_postgresql_notify.md` | 과거 측정 | 확인 | 7,347 RPS 수치의 오류 포함/수정 후 기록 충돌 확인 |
| `docs/06_Performance_Journey/10_real_data_challenge.md` | 과거 측정 | 확인 | 2026-03-24 wrk 조건과 결과; 추정치는 배제 |
| `docs/06_Performance_Journey/README.md` | 과거 측정 | 확인 | 성능 여정의 시계열·조건 분리 |
| `docs/18_Portfolio/external-api-pipeline-evolution.md` | 2차 서술 | 확인 | 현재 코드/커밋으로 다시 검증할 후보 발굴 |
| `docs/18_Portfolio/performance-optimization-portfolio-v2.md` | 2차 서술 | 확인 | 97→7,347을 동일 조건 배수로 표현하지 않도록 충돌 검출 |
| `docs/18_Portfolio/required_portfolio.md` | 제외 | 확인 | 프로젝트 고유 증거가 아닌 범용/과정 문서 |
| `docs/01_ADR/ADR-729-ext-api-item-equipment-loop-throughput.md` | ADR | 확인 | Proposed, 관측 결과 TBD—달성 수치로 사용 금지 |
| `docs/01_ADR/ADR-730_calculator-writer-temp-file-upload.md` | ADR·측정 | 확인 | pipe race와 temp-file 교체, 2026-06-22 MinIO E2E 정합성 회복 |
| `docs/01_ADR/ADR-736_disable-legacy-daily-cron.md` | ADR | 확인 | 이중 오케스트레이션 원인·수정 근거 |
| `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` | ADR | 확인 | 측정 환경 변화와 배포 결정 |
| `docs/01_ADR/ADR-740_retire-daily-full-pipeline.md` | ADR | 확인 | 운영 제어면 변화 |
| `docs/01_ADR/ADR-742_loop-upstream-defer.md` | ADR | 확인 | 후속 처리량 선택지와 유보 결정 |
| `docs/01_ADR/ADR-743-small-file-resolution.md` | ADR | 확인 | Proposed—구현 완료로 표현 금지 |
| `docs/01_ADR/ADR-744_internal-network-only-migration.md` | ADR | 확인 | 운영 경계와 제한 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` | 현재 코드 | 확인 | bounded queue·단일 writer·사전 직렬화·비동기 업로드 |
| `module-external-api/src/main/resources/application.yml` | 현재 설정 | 확인 | rate/in-flight/queue/chunk/Kafka ACK 조건 |
| `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` | 현재 코드 | 확인 | 결정적 결과 키·존재 시 재발행·재시도 |
| `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt` | 현재 코드 | 확인 | dispatch 성공 뒤 수동 ACK |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | 현재 코드 | 확인 | 스트리밍 처리 |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | 현재 코드 | 확인 | 임시 파일·비동기 업로드 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt` | 현재 코드 | 확인 | result-ready 투영 흐름 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt` | 현재 코드 | 확인 | read model write/upsert 경계 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt` | 현재 코드 | 확인 | DB claim/lease/state와 ACK 순서 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStateMachine.kt` | 현재 코드 | 확인 | retry/terminal 상태 결정 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` | 현재 코드 | 확인 | Redis multi-get 선조회·miss의 PostgreSQL batch read 경계 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt` | 현재 코드 | 확인 | Redis read-model cache multi-get/multi-put 구현 |
| `module-infra/src/main/resources/db/migration/V128__chunk_execution.sql` | 현재 스키마 | 확인 | chunk identity·lease·retry 상태 영속화 |
| `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt` | 현재 코드 | 확인 | 소비 완료 inbox/idempotency 경계 |

## 기존 Portfolio_Book 마크다운 분류

기존 장문 초안은 탐색용 2차 자료다. 시점이 다른 V1~V5, 제안 상태 ADR, 측정 조건이 다른 수치를 한 서술에 합친 부분이 있어 최종 결과의 단독 근거로 쓰지 않았다.

| 경로 | 분류 | 처리 |
|---|---|---|
| `docs/Portfolio_Book/00_프롤로그_시스템_개요.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/01_API_설계.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/02_도메인_모델링.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/03_아키텍처_진화.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/04_트랜잭션과_정합성.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/05_성능_엔지니어링.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/06_테스트_전략.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/07_관측성과_운영.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/08_보안과_안전장치.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |
| `docs/Portfolio_Book/09_한계와_다음단계.md` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |

## 전수 색인 집계

- Git 추적 파일 전체: 2,889개
- `docs/` 추적 파일: 1,117개
- ADR 후보: 199개
- 테스트 후보: 365개
- 현재 external-api/calculator/synchronizer/cleanup/rest-controller main 소스: 229개
- 성능·부하·장기 실행 경로 후보: 125개
- 아래 중복 제거 상세 카탈로그: 2,889개(이 조사 산출물 자체는 제외)

| 분류 | 파일 수 |
|---|---:|
| 2차 서술 | 2 |
| ADR | 198 |
| ADR·측정 | 1 |
| 과거 측정 | 3 |
| 기타 애플리케이션 코드 | 908 |
| 빌드·배포·설정 | 55 |
| 설계 | 1 |
| 설계·측정 | 1 |
| 설정·기타 | 179 |
| 성능·부하 도구 | 44 |
| 성능·부하 문서 | 64 |
| 저장소 문서 | 843 |
| 제외 | 1 |
| 측정 | 3 |
| 테스트 | 353 |
| 현재 설정 | 1 |
| 현재 스키마 | 1 |
| 현재 시스템 | 3 |
| 현재 코드 | 12 |
| 현재 파이프라인 코드 | 216 |

## 충돌·제외 규칙

- `97 RPS`와 `7,347 RPS`는 워크로드·시점·시스템 상태가 동일하다는 원시 기록이 없어 `76배` 단일 실험으로 표현하지 않는다.
- `7,347 RPS` 문서에는 최초 오류 65건과 후속 오류 0건 기록이 함께 있어, 최종 문구는 2026-03-24의 후속 조건과 한계를 명시한다.
- ADR-729의 목표치는 `Observed Result: TBD`, ADR-743은 `Proposed`이므로 달성/구현 성과로 쓰지 않는다.
- 71시간 보고서는 실행을 `~71h`로 표기하지만, 기재된 2026-06-23 09:03~06-26 05:30은 68h27m이다. 이 관측 창은 인프라·계산 안정성과 함께 03:00 처리 중단 2회와 수동 복구를 기록한다. 06-26의 dual-orchestration slot race는 직접 확인됐고 06-25는 같은 원인으로 소급 추정됐다. 이를 ‘race 2회 재현’이나 ‘완전 무인 안정성’으로 바꾸지 않는다.
- 보고서상 약 80시간 관측 창은 ITEM_EQUIPMENT phase이며, max connections/rate/in-flight 250은 이 endpoint 조건이다. 서비스별 uptime은 external-api/calculator 80h14m, synchronizer/cleanup 71h11m으로 다르다.
- 보고서의 `lifetime avg 136.57 users/s`은 38.08M users/80h14m 단순 산술값 약 131.84와 불일치한다. raw Prometheus query/window가 없으므로 최종 성과에서 제외하고, 35초의 약 71 records/s는 throughput이 아니라 queue-depth 순감소율로만 표현한다.
- 장기 테스트의 82시간·보고서상 71시간·보고서상 약 80시간 결과는 날짜, 배포 방식, 모듈 수, 서비스 uptime, 데이터량이 다르므로 서로 직접 합산하거나 동일 조건 비교하지 않는다.
- PR/이슈 본문, AI 요약, 커밋 제목은 의도·맥락 자료일 뿐 실제 적용 증거가 아니다. merge 상태, patch, 현재 코드, 측정 결과를 별도로 확인한다.
- 기존 문서의 추정 CPU 비율, 선형 확장 예상, 환산 처리량 등 원시 근거가 없는 숫자는 최종 성과에서 제외한다.

## 상세 카탈로그

`수동 심층 검토+교차검증`은 최종 주장 후보로 원문과 관련 코드/Git을 함께 읽었다는 뜻이다. 나머지는 누락 방지를 위해 경로와 텍스트를 전수 색인했으며, 이 표만으로 그 내부 주장을 승인하지 않는다.

| 경로 | 분류 | bytes | lines | 조사 방식 |
|---|---|---:|---:|---|
| `.agents/skills/gradle-build-performance/SKILL.md` | 성능·부하 도구 | 8,163 | 346 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.agents/skills/kotlin-springboot/SKILL.md` | 설정·기타 | 4,406 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.backup/build.gradle.backup_before_catalog` | 설정·기타 | 5,450 | 221 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.backup/docker_backup_temp/promtail/config.yml` | 빌드·배포·설정 | 571 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/pre-tool-use.sh` | 설정·기타 | 1,362 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/stop-validation.sh` | 설정·기타 | 603 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/trace-lib.sh` | 설정·기타 | 932 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/trace-prompt.sh` | 설정·기타 | 568 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/trace-session-init.sh` | 설정·기타 | 890 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/trace-stop.sh` | 설정·기타 | 1,301 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/hooks/trace-tool-use.sh` | 설정·기타 | 1,211 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/adr-conventions.md` | 설정·기타 | 2,056 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/architecture-guardrails.md` | 설정·기타 | 1,575 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/async-concurrency.md` | 설정·기타 | 2,865 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/async-patterns.md` | 설정·기타 | 2,927 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/build-conventions.md` | 설정·기타 | 1,281 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/code-rules.md` | 설정·기타 | 2,850 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/code-style.md` | 설정·기타 | 2,936 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/critical-rules.md` | 설정·기타 | 1,117 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/data-access.md` | 설정·기타 | 1,263 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/db-migration.md` | 설정·기타 | 1,272 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/kotlin-null-safety.md` | 설정·기타 | 1,028 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/load-test.md` | 성능·부하 도구 | 4,612 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/module-boundaries.md` | 설정·기타 | 1,761 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/mq-messaging.md` | 설정·기타 | 1,044 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/prometheus-metrics.md` | 설정·기타 | 4,554 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/rpi-workflow.md` | 설정·기타 | 1,691 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/security-rules.md` | 설정·기타 | 951 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/skill-routing.md` | 설정·기타 | 1,016 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/testing-conventions.md` | 설정·기타 | 1,701 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/workflow-rules.md` | 설정·기타 | 3,074 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/rules/yaml-config.md` | 설정·기타 | 897 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/settings.json` | 설정·기타 | 947 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.claude/skills/pipeline-test/SKILL.md` | 설정·기타 | 50,026 | 925 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.editorconfig` | 설정·기타 | 440 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.env.bak.euc-kr` | 설정·기타 | 1,165 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.env.example` | 설정·기타 | 3,081 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.gitattributes` | 설정·기타 | 88 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.github/workflows/ci.yml` | 빌드·배포·설정 | 22,416 | 518 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.github/workflows/gradle.yml` | 빌드·배포·설정 | 4,383 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.github/workflows/nightly.yml` | 빌드·배포·설정 | 8,922 | 280 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `.gitignore` | 설정·기타 | 2,262 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `AGENTS.md` | 설정·기타 | 3,222 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `CLAUDE.md` | 설정·기타 | 6,730 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `CONTRIBUTING.md` | 설정·기타 | 19,220 | 608 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `Note` | 설정·기타 | 0 | 0 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `README.md` | 현재 시스템 | 7,540 | 150 | 수동 심층 검토+교차검증 |
| `airflow/dags/cleanup_pipeline.py` | 설정·기타 | 1,747 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `build.gradle` | 빌드·배포·설정 | 9,487 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `characters.csv` | 설정·기타 | 75 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `config/pmd/ruleset.xml` | 설정·기타 | 1,522 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `config/sentinel/scripts/wait-for-redis.sh` | 설정·기타 | 201 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `config/sentinel/sentinel-1.conf` | 설정·기타 | 206 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `config/sentinel/sentinel-2.conf` | 설정·기타 | 206 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `config/sentinel/sentinel-3.conf` | 설정·기타 | 206 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `dev-tools/jdt-language-server-latest.tar.gz` | 설정·기타 | 51,368,896 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker-compose.airflow.yml` | 빌드·배포·설정 | 3,961 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker-compose.observability.yml` | 빌드·배포·설정 | 5,010 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker-compose.postgres.yml` | 빌드·배포·설정 | 1,506 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker-compose.services.yml` | 빌드·배포·설정 | 6,531 | 182 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker-compose.yml` | 빌드·배포·설정 | 11,599 | 330 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/Dockerfile.runtime` | 설정·기타 | 855 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/connections.sh` | 설정·기타 | 1,061 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/character_basic_pipeline.py` | 설정·기타 | 485 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/daily_cleanup_pipeline.py` | 설정·기타 | 2,503 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/daily_collection_pipeline.py` | 설정·기타 | 18,883 | 474 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/daily_full_pipeline.py` | 설정·기타 | 2,802 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/item_equipment_pipeline.py` | 설정·기타 | 485 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/morning_chain_pipeline.py` | 설정·기타 | 5,147 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/per_phase_tasks.py` | 설정·기타 | 14,622 | 399 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/phase_pipeline_factory.py` | 설정·기타 | 23,674 | 669 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/ranking_ocid_lookup_pipeline.py` | 설정·기타 | 3,033 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/stop_loop_pipeline.py` | 설정·기타 | 2,151 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/.airflowignore` | 설정·기타 | 2 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/__init__.py` | 설정·기타 | 0 | 0 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/conftest.py` | 설정·기타 | 836 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_dag_imports.py` | 설정·기타 | 1,297 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_morning_chain_pipeline.py` | 설정·기타 | 4,564 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_per_phase_tasks.py` | 설정·기타 | 17,440 | 476 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_phase_dag_structure.py` | 설정·기타 | 2,287 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_phase_pipeline_factory.py` | 설정·기타 | 28,395 | 711 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/airflow/dags/tests/test_sequence_steps.py` | 설정·기타 | 6,534 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/alertmanager/alertmanager.yml` | 빌드·배포·설정 | 2,959 | 117 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/compose/backup.sh` | 설정·기타 | 1,306 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/compose/docker-compose.yml` | 빌드·배포·설정 | 11,711 | 403 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/acl-pipeline-dashboard.json` | 설정·기타 | 22,261 | 898 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/application.json` | 설정·기타 | 4,900 | 214 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/cache-monitoring.json` | 설정·기타 | 16,579 | 556 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/lock-metrics.json` | 설정·기타 | 22,093 | 921 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-api-dashboard.json` | 설정·기타 | 13,272 | 593 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-buffer-dashboard.json` | 설정·기타 | 10,539 | 499 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-cache-dashboard.json` | 설정·기타 | 17,260 | 770 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-chaos-dashboard.json` | 설정·기타 | 17,311 | 782 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-database-dashboard.json` | 설정·기타 | 21,641 | 969 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-jvm-dashboard.json` | 설정·기타 | 15,763 | 656 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-lock-dashboard.json` | 설정·기타 | 18,070 | 746 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/maple-outbox-dashboard.json` | 설정·기타 | 12,775 | 573 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/prometheus-metrics.json` | 설정·기타 | 20,316 | 820 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/dashboards/slow-query.json` | 설정·기타 | 9,105 | 386 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/alerting/alerting.yml` | 빌드·배포·설정 | 230 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/alerts/maple-buffer-alerts.yaml` | 빌드·배포·설정 | 2,167 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/application.json` | 설정·기타 | 4,900 | 214 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/dashboard.yml` | 빌드·배포·설정 | 318 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/lock-metrics.json` | 설정·기타 | 21,822 | 906 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/main-dashboard.yml` | 빌드·배포·설정 | 232 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/maple-dashboard/business-dashboard.json` | 설정·기타 | 4,878 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/maple-dashboard/chaos-dashboard.json` | 설정·기타 | 4,415 | 178 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/maple-dashboard/system-dashboard.json` | 설정·기타 | 4,859 | 188 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/prometheus-metrics.json` | 설정·기타 | 20,316 | 820 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/dashboards/slow-query.json` | 설정·기타 | 9,137 | 387 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/datasources/loki.yml` | 빌드·배포·설정 | 329 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/grafana/provisioning/datasources/prometheus.yml` | 빌드·배포·설정 | 494 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/loki/loki-config.yaml` | 빌드·배포·설정 | 891 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/minio/bootstrap.sh` | 설정·기타 | 4,771 | 114 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/minio/policies/calculator.json` | 설정·기타 | 620 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/minio/policies/cleanup.json` | 설정·기타 | 421 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/minio/policies/ext-api.json` | 설정·기타 | 520 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/minio/policies/synchronizer.json` | 설정·기타 | 442 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/mysql/conf.d/my.cnf` | 설정·기타 | 1,818 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/postgres/init.sql` | 설정·기타 | 3,066 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/prometheus.yml` | 빌드·배포·설정 | 2,314 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/rules/alert_rules.yml` | 빌드·배포·설정 | 10,426 | 259 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/rules/cache-backend-alerts.yml` | 빌드·배포·설정 | 770 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/rules/load-test-rules.yml` | 빌드·배포·설정 | 3,017 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/rules/lock-alerts.yml` | 빌드·배포·설정 | 3,921 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/prometheus/rules/offheap-alerts.yml` | 빌드·배포·설정 | 890 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/promtail-config/config.yml` | 빌드·배포·설정 | 1,872 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/promtail/config.yml` | 빌드·배포·설정 | 2,696 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/services/build.sh` | 설정·기타 | 1,261 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docker/services/deploy-apps.sh` | 설정·기타 | 7,295 | 168 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.bkit-memory.json` | 저장소 문서 | 144 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.pdca-snapshots/snapshot-1769774901822.json` | 저장소 문서 | 508 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.pdca-snapshots/snapshot-1769783591817.json` | 저장소 문서 | 508 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.pdca-snapshots/snapshot-1769787380400.json` | 저장소 문서 | 508 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.pdca-snapshots/snapshot-1769793154654.json` | 저장소 문서 | 899 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/.pdca-status.json` | 저장소 문서 | 723 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/BUSINESS_MODEL.md` | 저장소 문서 | 12,157 | 361 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/CLAIM_EVIDENCE_MATRIX.md` | 저장소 문서 | 23,418 | 460 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/DESIGN_DECISIONS.md` | 저장소 문서 | 61,810 | 1175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/DOCS_SUMMARY.md` | 저장소 문서 | 9,864 | 262 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/MVP-ROADMAP.md` | 저장소 문서 | 14,268 | 440 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/ROADMAP.md` | 저장소 문서 | 30,966 | 604 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/SYSTEM_KNOWLEDGE_BASE.md` | 저장소 문서 | 40,710 | 626 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/architecture.md` | 저장소 문서 | 24,523 | 728 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/characterization-test-summary.md` | 저장소 문서 | 7,091 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/00_Start_Here/multi-agent-protocol.md` | 저장소 문서 | 8,569 | 218 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-001-streaming-parser.md` | ADR | 12,396 | 299 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-003-tiered-cache-singleflight.md` | ADR | 16,799 | 458 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-004-logicexecutor-policy-pipeline.md` | ADR | 15,106 | 427 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-005-resilience4j-scenario-abc.md` | ADR | 9,557 | 300 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-007-aop-async-cache-integration.md` | ADR | 11,127 | 330 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-008-durability-graceful-shutdown.md` | ADR | 6,926 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-009-cube-dp-calculator-probability.md` | ADR | 7,625 | 240 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-010-outbox-pattern.md` | ADR | 12,671 | 380 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-011-controller-v4-optimization.md` | ADR | 7,777 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-012-stateless-scalability-roadmap.md` | ADR | 12,348 | 412 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-013-high-throughput-event-pipeline.md` | ADR | 83,346 | 1880 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-014-multi-module-cross-cutting-concerns.md` | ADR | 15,732 | 352 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-015-like-endpoint-p1-acceptance.md` | ADR | 13,600 | 322 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-016-nexon-api-outbox-pattern.md` | ADR | 28,480 | 759 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-017-S1-equipment-slice.md` | ADR | 46,259 | 1311 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-018-acl-strategy-pattern.md` | ADR | 21,561 | 629 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-019-ultraqa-cycle2-solid-refactoring.md` | ADR | 11,478 | 305 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-020-flaky-test-fixing-solid-refactoring.md` | ADR | 18,296 | 493 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-022-redis-dependency-removal.md` | ADR | 9,436 | 269 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-025-chaos-test-module-separation.md` | ADR | 9,607 | 270 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-026-chunk-pipeline-orchestrator.md` | ADR | 4,096 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-027-batch-progress-sink-factory.md` | ADR | 3,762 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-034-scheduler-task-pool-configuration.md` | ADR | 23,312 | 556 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-035-issue-282-completion.md` | ADR | 5,648 | 167 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-036-v5-cqrs-mongodb.md` | ADR | 8,742 | 255 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-037-exception-translator-return-vs-throw.md` | ADR | 13,894 | 365 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-038-priority-queue-worker-isolation.md` | ADR | 15,387 | 423 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-039-current-architecture-assessment.md` | ADR | 18,578 | 499 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-040-chaos-engineering-documentation-update.md` | ADR | 10,131 | 317 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-041-multi-module-hexagonal-architecture-dip.md` | ADR | 23,147 | 539 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-042-v2-v4-dual-generation-architecture.md` | ADR | 18,319 | 494 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-043-tiered-cache.md` | ADR | 15,594 | 423 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-044-logicexecutor-zero-try-catch.md` | ADR | 28,068 | 735 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-045-virtual-threads-non-blocking.md` | ADR | 14,979 | 416 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-046-transactional-outbox.md` | ADR | 15,377 | 489 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-047-redisson-watchdog.md` | ADR | 16,319 | 462 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-048-java-21-virtual-threads.md` | ADR | 13,331 | 430 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-049-spring-boot-3.5.4-adoption.md` | ADR | 11,129 | 308 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-050-module-infra-decomposition-roadmap.md` | ADR | 12,073 | 245 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-051-mysql-testcontainers-adoPTION.md` | ADR | 10,327 | 353 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-052-resilience4j-circuit-breaker.md` | ADR | 17,913 | 480 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-053-observability-stack.md` | ADR | 13,450 | 436 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-054-github-actions-cicd.md` | ADR | 14,285 | 517 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-055-redis-streams.md` | ADR | 17,478 | 480 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-056-mongodb-cqrs-read-side.md` | ADR | 18,006 | 567 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-057-redisson-distributed-lock.md` | ADR | 20,173 | 517 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-058-caffeine-l1-cache.md` | ADR | 10,383 | 338 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-059-gradle-build-tool-adoption.md` | ADR | 22,678 | 643 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-061-flaky-test-tracking-quarantine.md` | ADR | 9,243 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-064-mysql-slow-query-prometheus.md` | ADR | 9,274 | 313 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-066-prometheus-ip-access-control.md` | ADR | 10,390 | 320 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-067-defensive-programming-nonblocking.md` | ADR | 11,128 | 360 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-071-connection-pool-alert-isolation.md` | ADR | 13,359 | 410 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-078-named-lock-circular-deadlock-prevention.md` | ADR | 14,683 | 495 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-079-v5-cqrs-flowchart-complete.md` | ADR | 33,245 | 944 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-080-v5-cqrs-worker-startup-fix.md` | ADR | 19,042 | 546 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-081-v5-cqrs-redis-stream-idempotency-fix.md` | ADR | 12,045 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-083-mongodb-sync-backward-compatibility.md` | ADR | 8,300 | 301 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-084-ocidreader-data-loss-state-fix.md` | ADR | 7,776 | 252 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-085-viewtransformer-decimal-parsing-fix.md` | ADR | 7,600 | 274 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-086-taskcontext-null-handling-kotlin-interop.md` | ADR | 8,781 | 292 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-087-p2-configuration-monitoring-fixes.md` | ADR | 6,725 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-088-hikaricp-virtual-thread-tuning.md` | ADR | 9,122 | 305 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-312-signal-deduplication-evidence-evaluation.md` | ADR | 35,431 | 931 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-313-fix-options-nullability.md` | ADR | 1,192 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-314-postgresql-single-db-strategy.md` | ADR | 7,313 | 263 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-315-module-separation-kotlin.md` | ADR | 918 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-316-pgmq-integration.md` | ADR | 9,859 | 380 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-317-hexagonal-architecture-adoption.md` | ADR | 7,925 | 211 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-318-postgresql-advisory-lock.md` | ADR | 17,702 | 559 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-319-postgresql-redis-replacement.md` | ADR | 10,936 | 396 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-320-collect-compute-serve-pipeline.md` | ADR | 22,526 | 610 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-321-postgresql-advisory-lock.md` | ADR | 14,031 | 517 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-322-single-flight-hot-key.md` | ADR | 19,397 | 638 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-323-postgresql-listen-notify.md` | ADR | 17,037 | 609 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-324-scaleout-strategy.md` | ADR | 21,412 | 731 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-325-postgresql-mongodb-replacement.md` | ADR | 11,126 | 429 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-326-alert-to-infra-migration.md` | ADR | 2,741 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-327-cache-to-infra-migration.md` | ADR | 3,841 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-328-service-layer-modularization.md` | ADR | 19,765 | 459 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-329-donation-outbox-infra-migration.md` | ADR | 6,269 | 172 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-330-equipment-cache-infra-migration.md` | ADR | 3,586 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-331-like-to-infra-migration-build-plan.md` | ADR | 2,334 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-332-like-to-infra-migration.md` | ADR | 4,864 | 123 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-333-redis-operation-port-abstraction.md` | ADR | 4,788 | 175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-334-multi-datasource-transaction-strategy.md` | ADR | 6,648 | 170 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-335-connection-pool-alignment.md` | ADR | 4,683 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-336-n-plus-one-query-optimization.md` | ADR | 4,947 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-337-jwt-algorithm-security.md` | ADR | 5,428 | 132 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-338-adaptive-micro-batching.md` | ADR | 21,351 | 521 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-340-mongodb-dependency-removal.md` | ADR | 3,769 | 120 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-341-mysql-dependency-removal.md` | ADR | 4,111 | 146 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-342-load-test-performance-evolution.md` | ADR | 32,793 | 734 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-343-bulk-loading-300k-characters.md` | ADR | 15,442 | 412 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-344-like-direct-db-approach.md` | ADR | 3,466 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-345-stateless-alert-system.md` | ADR | 18,670 | 602 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-346-like-fingerprint-account-id-trigger.md` | ADR | 5,346 | 143 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-347-fix-639-dip-violation-module-web-to-infra.md` | ADR | 3,920 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-348-fix-644-god-object-cache-coordinator.md` | ADR | 4,008 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-349-calculator-migration-summary.md` | ADR | 5,261 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-350-module-core-migration-cube-report.md` | ADR | 5,445 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-351-module-core-migration-cube-summary.md` | ADR | 3,241 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-352-module-core-migration.md` | ADR | 11,329 | 323 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-353-module-dependency-strategy.md` | ADR | 5,132 | 169 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-354-monitoring-infra-migration.md` | ADR | 4,398 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-355-fanout-queue-driven-pipeline.md` | ADR | 4,550 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-355-java-to-kotlin-migration-strategy.md` | ADR | 5,131 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-356-claude-code-hooks-guardrails-system.md` | ADR | 7,908 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-357-exception-classifier.md` | ADR | 7,169 | 144 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-358-observability-metrics-rules.md` | ADR | 7,096 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-359-observability-tracing-rules.md` | ADR | 7,024 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-360-l1-cache-version-tag-invalidation.md` | ADR | 3,556 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-360-pgmq-pipeline-load-test-tuning.md` | ADR | 4,203 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-361-like-buffer-restore-on-circuit-open.md` | ADR | 3,710 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-362-equipment-persistence-tracker-postgres.md` | ADR | 4,226 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-363-expectation-calculation-queue-pgmq.md` | ADR | 4,246 | 146 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-364-performance-analysis-20260324.md` | ADR | 31,205 | 785 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-367-starforce-migration-analysis.md` | ADR | 5,598 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-368-test-reboot-pyramid.md` | ADR | 13,768 | 460 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-369-domain-extraction-clean-architecture.md` | ADR | 35,718 | 975 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-370-multi-module-migration-completion.md` | ADR | 8,578 | 234 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-371-jdbc-batch-refactoring.md` | ADR | 5,278 | 175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-372-monitoring-config-infra-migration.md` | ADR | 11,127 | 268 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-373-reactive-scheduler-eager-execution.md` | ADR | 14,886 | 391 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-374-v5-cqrs-command-side.md` | ADR | 9,977 | 241 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-375-v5-cqrs-implementation.md` | ADR | 23,084 | 709 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-376-async-executor-alert-fixes.md` | ADR | 9,634 | 280 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-377-issue-356-batch-refresh.md` | ADR | 18,893 | 693 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-378-refresh-token-atomic-lua-script.md` | ADR | 10,229 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-379-cache-valuewrapper-unwrapping-fix.md` | ADR | 9,787 | 278 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-380-jdbc-batch-migration.md` | ADR | 11,312 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-381-facade-migration-analysis.md` | ADR | 7,300 | 232 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-382-starforce-migration-analysis.md` | ADR | 5,514 | 178 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-383-sync-fanout-cqrs-separation.md` | ADR | 11,303 | 274 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-384-v5-endpoint-performance-tuning.md` | ADR | 7,710 | 217 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-385-disable-legacy-polling-workers.md` | ADR | 2,033 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-386-explicit-jpa-transaction-manager.md` | ADR | 2,423 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-387-worker-batch-fanout-coalescing.md` | ADR | 3,125 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-388-inline-view-write-precomputed-read-model.md` | ADR | 3,210 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-389-request-key-active-job-dedup.md` | ADR | 3,292 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-390_artifact-retention-policy.md` | ADR | 2,951 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-391-outbound-port-seam-classification.md` | ADR | 3,657 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-392-gamecharacter-port-incomplete-extraction.md` | ADR | 5,807 | 111 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-393-airflow-per-phase-dag.md` | ADR | 3,882 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-704-multi-instance-cache-invalidation-test.md` | ADR | 3,106 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-715-716-cache-storage-migration-version-counter-fix.md` | ADR | 1,304 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-716_synchronizer-extract-chunk-processor.md` | ADR | 2,429 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-717-external-api-nexon-throughput-tuning.md` | ADR | 4,326 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-718_orchestration-airflow-evaluation.md` | ADR | 4,346 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-719_object-storage-abstraction-minio-readiness.md` | ADR | 4,791 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-720_airflow-control-plane-adoption.md` | ADR | 3,343 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-721_sync-boundary-justification.md` | ADR | 3,944 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-722_infrastructure-package-naming-policy.md` | ADR | 3,184 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` | ADR | 5,941 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-724-dedicated-cpu-executor-separation.md` | ADR | 5,728 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md` | ADR | 9,625 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-726-airflow-trigger-task-design.md` | ADR | 5,187 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-727_stale-kafka-run-handling.md` | ADR | 6,595 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-728_minio-key-rotation-deferred.md` | ADR | 6,106 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-729-ext-api-item-equipment-loop-throughput.md` | ADR | 5,322 | 104 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-730_calculator-writer-temp-file-upload.md` | ADR·측정 | 5,016 | 97 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-731_coolify-self-healing-infra.md` | ADR | 3,748 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-732_coolify-apps-image-pipeline.md` | ADR | 3,320 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-733_coolify-observability-autodeploy.md` | ADR | 3,419 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-734_phase-separated-dags.md` | ADR | 4,003 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` | ADR | 9,851 | 165 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-736_disable-legacy-daily-cron.md` | ADR | 4,060 | 96 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` | ADR | 5,273 | 101 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-738_airflow-db-port-publish.md` | ADR | 4,647 | 100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-739_loop-started-sensor-condition.md` | ADR | 3,977 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-740_retire-daily-full-pipeline.md` | ADR | 3,358 | 91 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-741_app-log-retention.md` | ADR | 3,140 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-742_loop-upstream-defer.md` | ADR | 4,678 | 98 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-743-small-file-resolution.md` | ADR | 6,106 | 113 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-744_internal-network-only-migration.md` | ADR | 5,816 | 139 | 수동 심층 검토+교차검증 |
| `docs/01_ADR/ADR-V5-cqrs-mongodb-readside.md` | ADR | 29,795 | 839 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-V5-query-server-nextjs-phase1.md` | ADR | 8,050 | 227 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-XXX_external-api-worker-cf-chaining.md` | ADR | 2,937 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-backpressure-concurrency-limits.md` | ADR | 2,863 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-blocking-async-contract-cf-chain.md` | ADR | 6,894 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-blocking-in-async-timeout.md` | ADR | 2,546 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-btree-jsonb-index-removal.md` | ADR | 3,168 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-hexagonal-architecture-violation-fixes.md` | ADR | 3,051 | 60 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-log-governance.md` | ADR | 2,656 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-module-calculator-evolution.md` | ADR | 8,035 | 177 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pgmq-atomic-dedup-monotonic-upsert.md` | ADR | 3,581 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pgmq-calculation-pipeline-perf-27x.md` | ADR | 20,633 | 519 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pgmq-kafka-migration.md` | ADR | 7,076 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pgmq-v5-pipeline-optimization.md` | ADR | 7,850 | 268 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pgmq-write-pipeline-debugging.md` | ADR | 8,629 | 245 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-pipeline-fan-out-restructuring.md` | ADR | 7,268 | 187 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-redis-distributed-cache-adoption.md` | ADR | 4,528 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-three-path-independence-mq-boundary.md` | ADR | 32,016 | 884 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/ADR-write-path-snapshot-calculator.md` | ADR | 8,895 | 258 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/_archive/ADR_ENHANCEMENT_COMPLETE.md` | ADR | 15,021 | 400 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/_archive/ADR_ENHANCEMENT_SUMMARY.md` | ADR | 14,350 | 372 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/_archive/README_01_ADR.md` | ADR | 6,201 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/_archive/README_01_Adr.md` | ADR | 9,466 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/01_ADR/diagrams/ADR-355-fanout-queue-driven-pipeline-diagram.md` | ADR | 13,187 | 408 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md` | 저장소 문서 | 18,811 | 518 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/01_Core/02-mysql-death.md` | 저장소 문서 | 48,275 | 1314 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/01_Core/03-oom.md` | 저장소 문서 | 25,654 | 764 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/02_Network/04-split-brain.md` | 저장소 문서 | 26,340 | 785 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/02_Network/05-clock-drift.md` | 저장소 문서 | 28,824 | 791 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/02_Network/06-slow-loris.md` | 저장소 문서 | 29,671 | 752 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/02_Network/07-black-hole-commit.md` | 저장소 문서 | 35,117 | 889 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/02_Network/12-gray-failure.md` | 저장소 문서 | 22,065 | 598 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/03_Resource/08-disk-full.md` | 저장소 문서 | 23,592 | 563 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/03_Resource/09-retry-storm.md` | 저장소 문서 | 30,036 | 778 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/03_Resource/10-pool-exhaustion.md` | 저장소 문서 | 27,710 | 723 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/03_Resource/11-gc-pause.md` | 저장소 문서 | 21,223 | 544 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/04_Connection/13-half-open-hell.md` | 저장소 문서 | 6,458 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/04_Connection/17-thundering-herd-lock.md` | 저장소 문서 | 8,881 | 228 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/05_Data/14-duplicate-delivery.md` | 저장소 문서 | 8,262 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/05_Data/15-out-of-order.md` | 저장소 문서 | 8,303 | 209 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/05_Data/16-config-poisoning.md` | 저장소 문서 | 10,315 | 274 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/DOCUMENTATION_ENHANCEMENTS_SUMMARY.md` | 저장소 문서 | 7,956 | 290 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N01-thundering-herd.md` | 저장소 문서 | 16,973 | 578 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N02-deadlock-trap.md` | 저장소 문서 | 14,204 | 473 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N03-thread-pool-exhaustion.md` | 저장소 문서 | 17,263 | 484 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N04-connection-vampire.md` | 저장소 문서 | 20,968 | 643 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N05-celebrity-problem.md` | 저장소 문서 | 25,992 | 770 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N06-timeout-cascade.md` | 저장소 문서 | 26,186 | 821 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N07-metadata-lock-freeze.md` | 저장소 문서 | 12,298 | 374 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N09-circular-lock-deadlock.md` | 저장소 문서 | 5,499 | 188 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N10-caller-runs-policy.md` | 저장소 문서 | 6,775 | 232 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N11-lock-fallback-avalanche.md` | 저장소 문서 | 8,681 | 282 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N12-async-context-loss.md` | 저장소 문서 | 9,495 | 283 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N13-zombie-outbox.md` | 저장소 문서 | 9,227 | 294 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N14-pipeline-exception.md` | 저장소 문서 | 9,026 | 256 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N15-aop-order-problem.md` | 저장소 문서 | 5,227 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N16-self-invocation.md` | 저장소 문서 | 8,950 | 298 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N17-poison-pill.md` | 저장소 문서 | 14,638 | 458 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N18-deep-paging.md` | 저장소 문서 | 9,285 | 312 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N19-compound-failures.md` | 저장소 문서 | 9,576 | 323 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N19-outbox-replay.md` | 저장소 문서 | 17,668 | 541 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Chaos_Engineering/06_Nightmare/TEST_CODE_ANALYSIS.md` | 저장소 문서 | 15,048 | 287 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/02_Investigations/2026-06-28-small-file-measurement.md` | 저장소 문서 | 8,898 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/DOCUMENTATION_ENHANCEMENT_SUMMARY.md` | 저장소 문서 | 11,759 | 326 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/Deliberate-Over-Engineering.md` | 저장소 문서 | 22,390 | 607 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/FLAME_LOGIC.md` | 저장소 문서 | 74,519 | 2198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/QA_MONITORING_CHECKLIST.md` | 저장소 문서 | 22,178 | 662 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/SRE_INFRASTRUCTURE_OPERATIONS_GUIDE.md` | 저장소 문서 | 45,527 | 1069 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/TEST-REWRITE-QUICK-REF.md` | 저장소 문서 | 3,054 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/ZERO_SCRIPT_QA_GUIDE.md` | 저장소 문서 | 25,616 | 784 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/architecture-decision-rules.md` | 저장소 문서 | 23,622 | 866 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/async-concurrency.md` | 저장소 문서 | 19,876 | 485 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/auto-warmup.md` | 저장소 문서 | 10,503 | 255 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/chaos-test-cicd-patterns.md` | 저장소 문서 | 16,245 | 568 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/chaos-test-implementation-summary.md` | 저장소 문서 | 13,337 | 351 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/chaos-test-module-architecture.md` | 저장소 문서 | 17,835 | 499 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/chaos-test-quick-start.md` | 저장소 문서 | 3,657 | 143 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/comprehensive-data-flow.md` | 저장소 문서 | 24,680 | 801 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/dto-ownership.md` | 저장소 문서 | 6,320 | 262 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/flaky-test-management.md` | 저장소 문서 | 11,687 | 430 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/infrastructure.md` | 저장소 문서 | 30,789 | 766 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/integration-testing-guide.md` | 저장소 문서 | 25,370 | 987 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/lock-strategy.md` | 저장소 문서 | 19,830 | 482 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/logic_executor_policy_pipeline.md` | 저장소 문서 | 71,242 | 1711 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/module-common-api-manifest.md` | 저장소 문서 | 29,022 | 874 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/module-wise-test-guide.md` | 저장소 문서 | 14,052 | 552 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/monitoring-copilot-implementation.md` | 저장소 문서 | 17,157 | 557 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/monitoring-copilot-troubleshooting.md` | 저장소 문서 | 8,467 | 353 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/port-guide.md` | 저장소 문서 | 5,999 | 211 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/resilience.md` | 저장소 문서 | 5,185 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/runbook.md` | 저장소 문서 | 7,200 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/scenario-planning.md` | 저장소 문서 | 41,027 | 1298 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/security-checklist.md` | 저장소 문서 | 17,106 | 359 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/security-hardening.md` | 저장소 문서 | 18,957 | 545 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/security-incident-response.md` | 저장소 문서 | 18,199 | 550 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/security-testing.md` | 저장소 문서 | 18,303 | 601 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/service-modules.md` | 저장소 문서 | 32,428 | 873 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/technology-decision-framework.md` | 저장소 문서 | 21,169 | 699 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/testcontainers-singleton-flaky-prevention.md` | 저장소 문서 | 7,330 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/testcontainers-singleton-implementation.md` | 저장소 문서 | 6,634 | 223 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/testing-guide.md` | 저장소 문서 | 25,981 | 741 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/03_Technical_Guides/workflow-integration-guide.md` | 저장소 문서 | 28,299 | 1017 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/README.md` | 저장소 문서 | 1,572 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/async-pipeline-sequence.md` | 저장소 문서 | 6,324 | 213 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/authentication-sequence.md` | 저장소 문서 | 6,673 | 220 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/cache-sequence.md` | 저장소 문서 | 4,649 | 169 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/character-lookup-sequence.md` | 저장소 문서 | 4,860 | 155 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/dp-calculator-sequence.md` | 저장소 문서 | 7,344 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/expectation-api-sequence.md` | 저장소 문서 | 8,029 | 260 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/expectation-cache-sequence.md` | 저장소 문서 | 8,759 | 263 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/expectation-calculation-sequence.md` | 저장소 문서 | 9,990 | 337 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/expectation-sequence-diagram.md` | 저장소 문서 | 17,638 | 502 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/like-realtime-sync-sequence.md` | 저장소 문서 | 5,879 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/logic-executor-sequence.md` | 저장소 문서 | 4,478 | 160 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/nexon-api-outbox-sequence.md` | 저장소 문서 | 14,775 | 453 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/outbox-sequence.md` | 저장소 문서 | 12,031 | 386 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/resilience-sequence.md` | 저장소 문서 | 5,496 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/resilient-lock-strategy-diagrams.md` | 저장소 문서 | 11,168 | 399 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/scheduler-data-flow-diagrams.md` | 저장소 문서 | 29,518 | 979 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/shutdown-sequence.md` | 저장소 문서 | 5,250 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/v5-cache-miss-flow.md` | 저장소 문서 | 19,218 | 459 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/v5-cqrs-sequence.md` | 저장소 문서 | 4,079 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/v5-endpoint-data-flow-architecture.md` | 저장소 문서 | 11,825 | 302 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/v5-highlevel-architecture.md` | 저장소 문서 | 3,036 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/04_Sequence_Diagrams/v5-query-server-separation.md` | 저장소 문서 | 5,691 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/BASELINE_20260210.md` | 저장소 문서 | 2,790 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/FINAL_SUMMARY_COMPLETE.md` | 저장소 문서 | 5,499 | 215 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/GENERATION_SUMMARY.md` | 저장소 문서 | 12,714 | 415 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/INDEX.md` | 저장소 문서 | 6,976 | 216 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/KPI_BSC_DASHBOARD.md` | 저장소 문서 | 18,228 | 467 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/METRIC_COLLECTION_EVIDENCE.md` | 저장소 문서 | 18,740 | 472 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/QUICK_REFERENCE.md` | 저장소 문서 | 12,275 | 394 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/README.md` | 저장소 문서 | 16,807 | 521 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_01_Baseline/observability.md` | 저장소 문서 | 21,602 | 706 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/COST_PERF_REPORT_N23.md` | 성능·부하 문서 | 27,729 | 735 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/COST_PERF_REPORT_N23_ACTUAL.md` | 성능·부하 문서 | 23,220 | 625 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/N23_V4_API_RESULTS.md` | 성능·부하 문서 | 17,164 | 466 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/N23_WRK_V4_RESULTS.md` | 성능·부하 문서 | 21,420 | 612 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/PERFORMANCE_260105.md` | 성능·부하 문서 | 12,272 | 364 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/Portfolio_Enhancement_Actual_Results.md` | 성능·부하 문서 | 25,291 | 793 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/Portfolio_Enhancement_Final_Summary.md` | 성능·부하 문서 | 21,633 | 648 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/high-traffic-performance-analysis.md` | 성능·부하 문서 | 29,268 | 849 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_02_Cost_Performance/p1-p2-performance-improvements-report.md` | 성능·부하 문서 | 24,081 | 780 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/5-agent-council-review-acl-implementation.md` | 저장소 문서 | 22,265 | 659 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md` | 저장소 문서 | 38,103 | 883 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE_ENHANCED.md` | 저장소 문서 | 15,628 | 326 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/DOCS_INTEGRITY_ENHANCEMENT_SUMMARY.md` | 저장소 문서 | 11,814 | 317 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/DOCUMENTATION_ENHANCEMENT_SUMMARY.md` | 저장소 문서 | 9,815 | 288 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/DOCUMENTATION_INTEGRITY_ENHANCEMENT_FINAL.md` | 저장소 문서 | 12,408 | 398 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/DOCUMENTATION_INTEGRITY_ULTRAWORK_COMPLETE.md` | 저장소 문서 | 9,534 | 307 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/Portfolio_Enhancement_Summary.md` | 저장소 문서 | 25,315 | 727 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/Portfolio_Enhancement_WRK_Final_Summary.md` | 저장소 문서 | 23,421 | 705 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/changelog.md` | 저장소 문서 | 7,765 | 177 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_03_Deep_Dive/closed-issues-completion.report.md` | 저장소 문서 | 36,071 | 970 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_04_E2E_Validation/E2E_VALIDATION_REPORT.md` | 저장소 문서 | 30,020 | 752 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/INCIDENT_REPORT_N21_ACTUAL.md` | 저장소 문서 | 16,580 | 495 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md` | 저장소 문서 | 33,452 | 864 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/P0_Issues_Resolution_Report_2026-01-20.md` | 저장소 문서 | 27,351 | 703 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/P1-7-8-9-scheduler-distributed-lock.md` | 저장소 문서 | 21,545 | 533 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/P1_Nightmare_Issues_Resolution_Report.md` | 저장소 문서 | 24,843 | 605 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/discord-webhook-fix-summary.md` | 저장소 문서 | 3,007 | 120 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/discord-webhook-root-cause-analysis.md` | 저장소 문서 | 4,210 | 148 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/issue-300-completion-summary.md` | 저장소 문서 | 21,098 | 477 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/issue-344-implementation-report.md` | 저장소 문서 | 23,627 | 783 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_05_Incidents/v5-cqrs-stream-consumption-issue.md` | 저장소 문서 | 16,865 | 468 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/2026-04-22-pipeline-perf-iteration.md` | 성능·부하 문서 | 5,876 | 201 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md` | 측정 | 12,262 | 264 | 수동 심층 검토+교차검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260120.md` | 성능·부하 문서 | 17,285 | 510 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260124_V4_PHASE2.md` | 성능·부하 문서 | 27,790 | 749 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260124_V4_SINGLEFLIGHT.md` | 성능·부하 문서 | 13,998 | 384 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md` | 성능·부하 문서 | 16,785 | 502 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md` | 성능·부하 문서 | 25,640 | 728 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260127_MULTI_INSTANCE_WARMUP.md` | 성능·부하 문서 | 18,971 | 496 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_20260127_V5_STATELESS.md` | 성능·부하 문서 | 23,544 | 663 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/LOAD_TEST_REPORT_FIXES_SUMMARY.md` | 성능·부하 문서 | 7,205 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/NON_DETERMINISTIC_TEST_AUDIT_REPORT.md` | 성능·부하 문서 | 16,685 | 404 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/V5_INLINE_VIEW_WRITE_LOADTEST_REPORT.md` | 성능·부하 문서 | 3,667 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/V5_LOADTEST_REPORT.md` | 성능·부하 문서 | 23,131 | 616 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/baseline-report-2026-03-19.md` | 성능·부하 문서 | 7,787 | 275 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/flaky-test-fixing-report-issues-328-330.md` | 성능·부하 문서 | 8,045 | 281 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/monitoring-dashboard-flaky-tests.md` | 성능·부하 문서 | 9,941 | 370 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/pure-logic-test-migration-summary.md` | 성능·부하 문서 | 6,302 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/report-template.md` | 성능·부하 문서 | 5,634 | 232 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-categorization-report.md` | 성능·부하 문서 | 18,314 | 436 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-classification-migration-plan.md` | 성능·부하 문서 | 15,022 | 418 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-reboot-completion-report.md` | 성능·부하 문서 | 5,843 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-reboot-monitoring-analysis-report.md` | 성능·부하 문서 | 14,133 | 394 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-reboot-ultrawork-final-complete-report.md` | 성능·부하 문서 | 6,555 | 217 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-reboot-ultrawork-final-report.md` | 성능·부하 문서 | 6,818 | 238 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-reboot-ultrawork-session-complete.md` | 성능·부하 문서 | 9,025 | 289 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-rewrite-progress-phase1-2.md` | 성능·부하 문서 | 9,936 | 315 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-rewrite-progress-report.md` | 성능·부하 문서 | 5,543 | 196 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-rewrite-progress-visual.md` | 성능·부하 문서 | 9,159 | 316 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-rewrite-systematic-plan.md` | 성능·부하 문서 | 10,662 | 364 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_06_Load_Tests/test-rewrite-ultrawork-final-summary.md` | 성능·부하 문서 | 12,429 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md` | 저장소 문서 | 19,189 | 522 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ADR017_PREPARATION_COMPLETE.md` | 저장소 문서 | 16,612 | 482 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ARCHITECTURE_MAP.md` | 저장소 문서 | 29,113 | 689 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ARCHUNIT_RULES.md` | 저장소 문서 | 4,737 | 169 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/AUDIT_BASELINE.md` | 저장소 문서 | 28,379 | 706 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/BASE_INTERFACES.md` | 저장소 문서 | 24,305 | 778 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/CHARACTERIZATION_TESTS.md` | 저장소 문서 | 18,395 | 525 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/CI_STRATEGY.md` | 저장소 문서 | 13,089 | 558 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/CODE_QUALITY_ANALYSIS_2026-02-08.md` | 저장소 문서 | 8,320 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/COUNCIL_REVIEW_ADR017_PREPARATION.md` | 저장소 문서 | 21,250 | 606 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/FORMATTING_STANDARDS.md` | 저장소 문서 | 5,344 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/IMPLEMENTATION_READINESS.md` | 저장소 문서 | 7,722 | 285 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PACKAGE_STRUCTURE.md` | 저장소 문서 | 21,049 | 781 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PERFORMANCE_BASELINE.md` | 성능·부하 문서 | 13,275 | 427 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE0_SUMMARY.md` | 저장소 문서 | 16,000 | 505 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE1_CI_COMPLETE.md` | 저장소 문서 | 5,233 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE1_SUMMARY.md` | 저장소 문서 | 11,198 | 429 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE2_SUMMARY.md` | 저장소 문서 | 14,570 | 461 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_AGENT_REVIEW.md` | 저장소 문서 | 39,062 | 1233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_BASELINE_METRICS.md` | 저장소 문서 | 28,108 | 884 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_BUILD_FIXES_SUMMARY.md` | 저장소 문서 | 6,085 | 185 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_CHARACTERIZATION_ABORTED.md` | 저장소 문서 | 7,155 | 250 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_CHARACTERIZATION_SUMMARY.md` | 저장소 문서 | 10,606 | 348 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_PREPARATION_COMPLETE.md` | 저장소 문서 | 13,099 | 428 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_PREPARATION_SUMMARY.md` | 저장소 문서 | 10,359 | 342 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE3_QUICKSTART.md` | 저장소 문서 | 8,358 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/PHASE_2A_SUMMARY.md` | 저장소 문서 | 5,899 | 198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/QA_REPORT_CHARACTERIZATION_TESTS.md` | 저장소 문서 | 8,728 | 265 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/README.md` | 저장소 문서 | 15,056 | 463 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/RED_AGENT_REVIEW_ADR-017.md` | 저장소 문서 | 17,109 | 483 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/REFACTOR_PLAN.md` | 저장소 문서 | 20,028 | 737 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/RESILIENCE_BASELINE.md` | 저장소 문서 | 20,139 | 572 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/RISK_REGISTER.md` | 저장소 문서 | 19,026 | 607 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/S1_EQUIPMENT_SLICE_COMPLETE.md` | 저장소 문서 | 10,737 | 342 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/SOLID_100_PERCENT_COMPLETE.md` | 저장소 문서 | 10,173 | 361 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/SOLID_VIOLATIONS.md` | 저장소 문서 | 40,128 | 1434 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/SPOTBUGS_BASELINE.md` | 저장소 문서 | 7,662 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/SPOTLESS_PHASE1_REPORT.md` | 저장소 문서 | 8,531 | 335 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/STATEFUL_REFACTORING_TARGETS.md` | 저장소 문서 | 70,426 | 1658 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/TARGET_STRUCTURE.md` | 저장소 문서 | 27,958 | 851 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAQA-CYCLE2-COMPREHENSIVE-REFACTORING-REPORT.md` | 저장소 문서 | 11,861 | 369 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAQA-CYCLE2-P1-REFACTORING-EXECUTION-REPORT.md` | 저장소 문서 | 10,301 | 370 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAWORK-COMPLETION-STATUS.md` | 저장소 문서 | 12,089 | 411 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAWORK_FINAL_PHASE2_COMPLETE.md` | 저장소 문서 | 21,872 | 590 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAWORK_ISSUES_331_333_COMPLETE.md` | 저장소 문서 | 13,878 | 383 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/ULTRAWORK_PHASE2_OPERATIONS_FIX_SUMMARY.md` | 저장소 문서 | 14,771 | 429 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/acl-phase2-final-report.md` | 저장소 문서 | 32,770 | 941 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/acl-phase2-implementation-summary.md` | 저장소 문서 | 8,203 | 244 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/adr-014-analysis-and-recommendations.md` | 저장소 문서 | 13,580 | 450 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/aop-async-pipeline-p0-p1-refactoring-report.md` | 저장소 문서 | 22,603 | 571 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/cleancode-analysis-2026-02-08.md` | 저장소 문서 | 15,190 | 493 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/comprehensive-analysis-report.md` | 저장소 문서 | 5,340 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/cube-decorator-refactoring-report.md` | 저장소 문서 | 7,628 | 250 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/duplicated-code-analysis.md` | 저장소 문서 | 28,481 | 864 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/graceful-shutdown-p0-p1-refactoring-report.md` | 저장소 문서 | 28,095 | 674 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/like-endpoint-p0p1-analysis.md` | 저장소 문서 | 27,022 | 683 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/logicexecutor-pipeline-architecture-analysis.md` | 저장소 문서 | 30,419 | 789 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/monitoring-pipeline-refactoring-report.md` | 저장소 문서 | 10,358 | 328 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/monitoring_query_guide.md` | 저장소 문서 | 11,010 | 435 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/outbox-p0-p1-refactoring-report.md` | 저장소 문서 | 28,527 | 701 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/security-audit-report-2026-02-08.md` | 저장소 문서 | 19,682 | 714 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/test-inventory.sh` | 저장소 문서 | 5,852 | 188 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/v2_like_flow_analysis.md` | 저장소 문서 | 19,807 | 661 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_08_Refactor/v4-expectation-endpoint-p0-p1-analysis.md` | 저장소 문서 | 26,828 | 724 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_09_Scale_Out/3_INSTANCE_TEST_RESULTS.md` | 저장소 문서 | 15,711 | 479 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md` | 저장소 문서 | 33,682 | 858 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_09_Scale_Out/troubleshooting-kotlin-interop-2026-02-26.md` | 저장소 문서 | 11,235 | 371 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_09_Scale_Out/v5-cqrs-implementation-report.md` | 저장소 문서 | 23,092 | 690 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_09_Scale_Out/v5-cqrs-implementation-summary.md` | 저장소 문서 | 7,817 | 222 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_10_Unit3_Query_Injection_Audit/SQL_INJECTION_AUDIT_REPORT.md` | 저장소 문서 | 8,400 | 245 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_10_Unit3_Query_Injection_Audit/UNIT3_SUMMARY.md` | 저장소 문서 | 6,025 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/05_11_Unit4_Event_Ordering/UNIT4_IMPLEMENTATION_SUMMARY.md` | 저장소 문서 | 9,093 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/2026-06-18-blocking-audit.md` | 저장소 문서 | 11,519 | 147 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md` | 저장소 문서 | 11,688 | 335 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/BOTTLENECK_ANALYSIS_20260324.md` | 저장소 문서 | 40,992 | 1082 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/CODE_REVIEW_SIMPLIFY_REPORT.md` | 저장소 문서 | 6,300 | 167 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/CONCURRENCY-VERIFICATION-REPORT.md` | 저장소 문서 | 11,583 | 375 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/IMPLEMENTATION-VERIFICATION-COMPREHENSIVE-REPORT.md` | 저장소 문서 | 17,570 | 538 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/LOAD_TEST_PGMQ_PIPELINE_20260420.md` | 성능·부하 문서 | 4,078 | 125 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/LOAD_TEST_PGMQ_PIPELINE_20260420_R2.md` | 성능·부하 문서 | 7,383 | 226 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/MONITORING_COPILOT_SUMMARY.md` | 저장소 문서 | 14,832 | 372 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/Multi-Module-Refactoring-Analysis.md` | 저장소 문서 | 19,023 | 518 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/P0-P1-P2-FIXES-COMPLETE.md` | 저장소 문서 | 6,328 | 188 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/P0-P1-P2-FIXES-SUMMARY.md` | 저장소 문서 | 9,401 | 288 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PHASE3-INFRASTRUCTURE-MOVE-SUMMARY.md` | 저장소 문서 | 10,447 | 295 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PORTFOLIO_TECHNICAL_DEEP_DIVE.md` | 저장소 문서 | 23,932 | 699 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_349-466_ADR_Analysis_Integrated_Report.md` | 저장소 문서 | 10,131 | 305 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_ADR_Analysis_349-365.md` | 저장소 문서 | 9,802 | 296 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_ADR_Analysis_390-396.md` | 저장소 문서 | 9,069 | 261 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_ADR_Analysis_397-408.md` | 저장소 문서 | 7,812 | 271 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_ADR_Analysis_444-457.md` | 저장소 문서 | 9,143 | 253 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/PR_ADR_Analysis_458-466.md` | 저장소 문서 | 12,575 | 397 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/QUERY_PLAN_ANALYSIS_READ_MODEL.md` | 저장소 문서 | 12,268 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/REFACTORING-EXECUTIVE-SUMMARY.md` | 저장소 문서 | 11,550 | 331 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SCALE-OUT-ARCHITECTURE-READINESS-REPORT.md` | 저장소 문서 | 21,606 | 654 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SCORE_IMPROVEMENT_SUMMARY.md` | 저장소 문서 | 12,583 | 279 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SECURITY-AUDIT-REPORT.md` | 저장소 문서 | 19,002 | 534 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SLOW_TASK_ANALYSIS.md` | 저장소 문서 | 3,985 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SOLID-Principles-Verification-Report.md` | 저장소 문서 | 18,437 | 613 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/SOLID-Verification-Tests-Summary.md` | 저장소 문서 | 12,047 | 388 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/STAKEHOLDER_REVIEW.md` | 저장소 문서 | 15,724 | 446 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/STATELESS-DESIGN-VERIFICATION-REPORT.md` | 저장소 문서 | 24,706 | 656 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ULTRAWORK-FINAL-SUMMARY.md` | 저장소 문서 | 9,540 | 280 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ULTRAWORK-PHASE0-1-COMPLETE.md` | 저장소 문서 | 14,636 | 457 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ULTRAWORK-REFACTORING-SUMMARY.md` | 저장소 문서 | 14,558 | 404 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ULTRAWORK-SESSION-COMPLETE.md` | 저장소 문서 | 9,491 | 312 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ULTRAWORK-SESSION-SUMMARY.md` | 저장소 문서 | 9,461 | 260 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/ZERO_SCRIPT_QA_STATUS.md` | 저장소 문서 | 8,539 | 326 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-001438.md` | 저장소 문서 | 2,135 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-001606.md` | 저장소 문서 | 2,109 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-001728.md` | 저장소 문서 | 2,147 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-003553.md` | 저장소 문서 | 1,843 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-111627.md` | 저장소 문서 | 2,346 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-141058.md` | 저장소 문서 | 1,430 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-141954.md` | 저장소 문서 | 2,327 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-153000.md` | 저장소 문서 | 2,327 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-171619.md` | 저장소 문서 | 2,146 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-194833.md` | 저장소 문서 | 2,197 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-202118.md` | 저장소 문서 | 2,187 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-210241.md` | 저장소 문서 | 2,429 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-210822.md` | 저장소 문서 | 2,016 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-211526.md` | 저장소 문서 | 2,016 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-213359.md` | 저장소 문서 | 2,041 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-220949.md` | 저장소 문서 | 2,041 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-223747.md` | 저장소 문서 | 1,909 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-01-230159.md` | 저장소 문서 | 1,877 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-135835.md` | 저장소 문서 | 2,042 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-144854.md` | 저장소 문서 | 2,042 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-154638.md` | 저장소 문서 | 1,931 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-154857.md` | 저장소 문서 | 1,931 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-214341.md` | 저장소 문서 | 1,783 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-214458.md` | 저장소 문서 | 1,783 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-02-220954.md` | 저장소 문서 | 1,710 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-000127.md` | 저장소 문서 | 1,909 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-014249.md` | 저장소 문서 | 2,075 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-020623.md` | 저장소 문서 | 2,075 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-172430.md` | 저장소 문서 | 2,099 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-183211.md` | 저장소 문서 | 2,303 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-03-201408.md` | 저장소 문서 | 2,313 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-04-123713.md` | 저장소 문서 | 2,168 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-010035.md` | 저장소 문서 | 2,312 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-022056.md` | 저장소 문서 | 2,320 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-065429.md` | 저장소 문서 | 2,461 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-074816.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-075156.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-075518.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-212547.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-224750.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-224936.md` | 저장소 문서 | 2,051 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/_archive/session-reports/session-report-2026-03-05-225912.md` | 저장소 문서 | 2,136 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/api-backward-compatibility.md` | 저장소 문서 | 15,090 | 496 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/api-compatibility-assessment.md` | 저장소 문서 | 10,608 | 358 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/circular-dependency-analysis.md` | 저장소 문서 | 17,600 | 517 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/circular-dependency-resolution-report.md` | 저장소 문서 | 23,169 | 618 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/circular-dependency-violations-2026-02-16.md` | 저장소 문서 | 18,711 | 618 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/closed-issues-gap-analysis.md` | 저장소 문서 | 32,438 | 710 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/codebase-comprehensive-analysis-report.md` | 저장소 문서 | 27,265 | 822 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/event-architecture-verification.md` | 저장소 문서 | 4,937 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/flaky-test-prevention-verification.md` | 저장소 문서 | 15,314 | 470 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/grafana-dashboard-after-refactoring.json` | 저장소 문서 | 5,852 | 212 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/grafana-dashboard-before-refactoring.json` | 저장소 문서 | 5,055 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/implementation-progress.md` | 저장소 문서 | 3,383 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/implementation-summary-phase1.md` | 저장소 문서 | 4,170 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/implementation-summary-phase2.md` | 저장소 문서 | 4,069 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/implementation-summary-phase3.md` | 저장소 문서 | 4,888 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/in-memory-to-redis-analysis.md` | 저장소 문서 | 24,264 | 741 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/issue-354-redis-stream-fix-report.md` | 저장소 문서 | 8,616 | 297 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/legacy-storage-migration-analysis.md` | 저장소 문서 | 10,507 | 303 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/logic-executor-compliance.md` | 저장소 문서 | 7,483 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/metrics-implementation-plan.md` | 저장소 문서 | 38,246 | 1223 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/module-migration-progress-report.md` | 저장소 문서 | 3,976 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/module-structure-summary.md` | 저장소 문서 | 5,055 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/module-structure-verification-report.md` | 저장소 문서 | 11,912 | 358 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/monitoring-and-metrics-report.md` | 저장소 문서 | 26,978 | 924 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/multi-module-refactoring-phase2-3-completion-report.md` | 저장소 문서 | 10,427 | 290 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/nexon-api-fanout-analysis.md` | 저장소 문서 | 7,151 | 198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/phase1-module-common-summary.md` | 저장소 문서 | 6,181 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/phase2b-implementation-plan.md` | 저장소 문서 | 38,312 | 1173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/phase2b-verification-results.md` | 저장소 문서 | 10,792 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/redis-trust-broken-experience.md` | 저장소 문서 | 17,570 | 447 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/refactoring-analysis.md` | 저장소 문서 | 15,731 | 380 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/refactoring-completion.md` | 저장소 문서 | 19,122 | 500 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/rollback-strategy.md` | 저장소 문서 | 27,923 | 1064 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/slow-task-source-report.md` | 저장소 문서 | 89,490 | 1041 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/solid-verification-tests.md` | 저장소 문서 | 19,998 | 672 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/stateless-design-compliance.md` | 저장소 문서 | 12,512 | 328 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/stateless-design-verification.md` | 저장소 문서 | 14,209 | 388 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/top5-slow-task-bottleneck-analysis.md` | 저장소 문서 | 11,517 | 283 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/v5-cqrs-code-review-report.md` | 저장소 문서 | 13,839 | 437 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/v5-cqrs-fixes-summary.md` | 저장소 문서 | 6,824 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/v5-cqrs-verification-report.md` | 저장소 문서 | 12,157 | 293 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/05_Reports/zero-script-qa-2026-01-30.md` | 저장소 문서 | 22,140 | 560 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/00_prologue.md` | 성능·부하 문서 | 2,632 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/01_chaos_baseline.md` | 성능·부하 문서 | 3,513 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/02_singleflight_regression.md` | 성능·부하 문서 | 4,511 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/03_l1_fast_path.md` | 성능·부하 문서 | 5,640 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/04_write_behind_buffer.md` | 성능·부하 문서 | 5,402 | 148 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/05_parallel_presets.md` | 성능·부하 문서 | 5,795 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/06_stateless_tradeoff.md` | 성능·부하 문서 | 6,713 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/07_auto_warmup.md` | 성능·부하 문서 | 4,417 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/08_great_migration.md` | 성능·부하 문서 | 8,783 | 198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/09_postgresql_notify.md` | 과거 측정 | 15,218 | 269 | 수동 심층 검토+교차검증 |
| `docs/06_Performance_Journey/10_real_data_challenge.md` | 과거 측정 | 12,690 | 279 | 수동 심층 검토+교차검증 |
| `docs/06_Performance_Journey/11_fanout_admission_control.md` | 성능·부하 문서 | 9,922 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/12_epilogue.md` | 성능·부하 문서 | 10,023 | 178 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/13_pipeline_restructuring_supabase.md` | 성능·부하 문서 | 24,372 | 646 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/README.md` | 과거 측정 | 5,256 | 89 | 수동 심층 검토+교차검증 |
| `docs/06_Performance_Journey/calculation-parallelization-experiment.md` | 성능·부하 문서 | 4,717 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/performance-optimization-portfolio.md` | 성능·부하 문서 | 3,446 | 182 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/step-trace-load-test-report.md` | 성능·부하 문서 | 24,995 | 707 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/06_Performance_Journey/steptrace-slow-task-report.md` | 성능·부하 문서 | 18,291 | 417 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/01_Concurrency_and_Lock.md` | 저장소 문서 | 13,838 | 402 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/02_Memory_Hierarchy_and_Cache.md` | 저장소 문서 | 16,239 | 462 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/03_Resilience_Engineering.md` | 저장소 문서 | 15,339 | 432 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/04_Database_Internals_and_Batch.md` | 저장소 문서 | 21,697 | 577 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/05_Asynchronous_Programming.md` | 저장소 문서 | 17,091 | 517 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/06_Design_Patterns_and_Proxy.md` | 저장소 문서 | 19,456 | 635 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/07_High_Precision_Computing.md` | 저장소 문서 | 14,528 | 484 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/07_Deep_Dive_Textbook/COMPLETE_GUIDE.html` | 저장소 문서 | 12,151 | 290 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/PORTFOLIO.md` | 저장소 문서 | 9,424 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/api/v4_specification.md` | 저장소 문서 | 29,935 | 816 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/balanced-scorecard-kpis.md` | 저장소 문서 | 20,930 | 570 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/business-model-canvas.md` | 저장소 문서 | 9,909 | 311 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/demo/DEMO_GUIDE.md` | 저장소 문서 | 18,978 | 496 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/design/KAFKA_EDA_MIGRATION.md` | 저장소 문서 | 10,208 | 314 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/go-to-market-strategy.md` | 저장소 문서 | 23,446 | 715 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/08_Design_Research/user-personas-journeys.md` | 저장소 문서 | 23,716 | 548 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-02-26-adr-consolidation-validation-design.md` | 저장소 문서 | 3,032 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-02-26-adr-consolidation-validation-impl-plan.md` | 저장소 문서 | 10,828 | 416 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-02-27-module-separation-design.md` | 저장소 문서 | 9,682 | 337 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-02-27-module-separation-implementation.md` | 저장소 문서 | 24,519 | 923 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-02-27-module-separation-kotlin-implementation.md` | 저장소 문서 | 14,605 | 619 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-03-06-code-review-implementation-plan.md` | 저장소 문서 | 9,112 | 339 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-03-06-postgresql-migration-design.md` | 저장소 문서 | 7,563 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-03-06-postgresql-migration-issues.md` | 저장소 문서 | 25,495 | 867 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-03-08-spring-batch-findings.md` | 저장소 문서 | 8,259 | 228 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-04-01-fanout-batch-worker-with-coalescing.md` | 저장소 문서 | 12,686 | 318 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-04-03-fanout-queue-driven-pipeline.md` | 저장소 문서 | 24,957 | 685 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-04-05-worker-batch-fanout-coalescing.md` | 저장소 문서 | 6,908 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-04-19-two-phase-batch-upsert.md` | 저장소 문서 | 30,414 | 776 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-04-28-external-api-boundary-separation.md` | 저장소 문서 | 48,657 | 1361 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/2026-05-29-airflow-control-plane-adoption.md` | 저장소 문서 | 45,430 | 1330 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/code-review-jsonb-read-model-adr111.md` | 저장소 문서 | 19,061 | 598 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/issue-704-multi-instance-cache-invalidation-test.md` | 저장소 문서 | 8,558 | 204 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/issues-645-650-resolution-plan.md` | 저장소 문서 | 26,467 | 611 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/issues-651-655-resolution-plan.md` | 저장소 문서 | 14,727 | 346 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/issues-715-716-cache-storage-migration-version-counter-fix.md` | 저장소 문서 | 3,980 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/like-domain-662-665-plan.md` | 저장소 문서 | 14,396 | 357 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/loadtest-postmortem-2026-04-19.md` | 저장소 문서 | 6,525 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/open-issues-remediation-plan-2026-04-17.md` | 저장소 문서 | 2,437 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/outbox-to-pgmq-migration.md` | 저장소 문서 | 45,280 | 1059 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/postgresql-migration-phase2-plan.md` | 저장소 문서 | 7,042 | 210 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/project_tech_debt260308.md` | 저장소 문서 | 22,749 | 415 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/v5-query-server-nextjs-implementation-plan.md` | 저장소 문서 | 36,962 | 1012 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/09_Plans/v5-query-server-nextjs-separation.md` | 저장소 문서 | 8,117 | 259 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/MIGRATION_PLAN.md` | 저장소 문서 | 8,462 | 270 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/MIGRATION_STATUS.md` | 저장소 문서 | 8,198 | 252 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/V100__hot_key_counter.sql` | 저장소 문서 | 994 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/deletion-targets.md` | 저장소 문서 | 17,292 | 522 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/e2e-test-recipe.md` | 저장소 문서 | 4,582 | 205 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/intellij-kotlin-conversion-guide.md` | 저장소 문서 | 4,854 | 248 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/10_Migration/local-db-connection-guide.md` | 저장소 문서 | 6,581 | 302 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/2026-05-06-pipeline-metrics.md` | 저장소 문서 | 4,416 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/OBSERVABILITY_SYSTEM.md` | 저장소 문서 | 27,135 | 1129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/bug-scan-2026-05-31.md` | 저장소 문서 | 6,756 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/flat-coroutine-pipeline-analysis.md` | 저장소 문서 | 2,963 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/monitor-pipeline.sh` | 저장소 문서 | 4,263 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/11_Observability/slow-task-analysis-2026-04-30.md` | 저장소 문서 | 13,917 | 332 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/chunk-replay-architecture.md` | 저장소 문서 | 5,295 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/compatibility.md` | 저장소 문서 | 6,925 | 316 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/contract-v1.md` | 저장소 문서 | 6,916 | 182 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/samples/cache-invalidated.v1.md` | 저장소 문서 | 8,685 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/samples/character-calculated.v1.md` | 저장소 문서 | 5,734 | 167 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/12_Events/samples/donation-created.v1.md` | 저장소 문서 | 5,926 | 184 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/00_prologue.md` | 저장소 문서 | 5,194 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/01_misalignment.md` | 저장소 문서 | 4,940 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/02_alignment_fix.md` | 저장소 문서 | 4,791 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/03_scale_out_wall.md` | 저장소 문서 | 4,546 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/04_great_migration.md` | 저장소 문서 | 6,014 | 178 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/05_advisory_lock.md` | 저장소 문서 | 6,233 | 183 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/06_outbox_problem.md` | 저장소 문서 | 8,087 | 202 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/07_pgmq_unification.md` | 저장소 문서 | 12,516 | 315 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/08_code_story.md` | 저장소 문서 | 13,918 | 385 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/09_epilogue.md` | 저장소 문서 | 6,715 | 157 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Connection_Pool_Journey/README.md` | 저장소 문서 | 4,879 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Integration_Test/spring-boot-test-rules.md` | 저장소 문서 | 7,242 | 265 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Integration_Test/test-infra-verification.md` | 저장소 문서 | 9,435 | 304 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/13_Integration_Test/testcontainers-rules.md` | 저장소 문서 | 11,465 | 317 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N19/backlog-screenshot.md` | 저장소 문서 | 1,016 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N19/reconciliation.sql` | 저장소 문서 | 1,225 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N19/replay.log` | 저장소 문서 | 2,466 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N21/audit.log` | 저장소 문서 | 1,575 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N21/latency-spike-screenshot.md` | 저장소 문서 | 1,152 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N21/promql.txt` | 저장소 문서 | 1,410 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N23/comparison-screenshot.md` | 저장소 문서 | 2,383 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N23/cost_formula.md` | 저장소 문서 | 2,850 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/N23/k6_raw.json` | 저장소 문서 | 3,687 | 140 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/sre-copilot/audit-INC-29506523.json` | 저장소 문서 | 3,623 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/14_Evidence/sre-copilot/discord-alert-screenshot.md` | 저장소 문서 | 1,269 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/15_Issues/V5_CQRS_MONGODB_SYNC_ISSUE.md` | 저장소 문서 | 14,362 | 450 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/15_Issues/V5_CQRS_Redis_Stream_Consumption_Issue.md` | 저장소 문서 | 8,716 | 266 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/HOOK_GUIDE.md` | 저장소 문서 | 16,799 | 533 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/INDEX.json` | 저장소 문서 | 29,972 | 596 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/INDEX.md` | 저장소 문서 | 13,372 | 278 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/TEST_REPORT.md` | 저장소 문서 | 3,190 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/INDEX.md` | 저장소 문서 | 4,316 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/adr-decisions.md` | 저장소 문서 | 19,429 | 623 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/clean-code.md` | 저장소 문서 | 17,367 | 630 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/multi-agent.md` | 저장소 문서 | 12,528 | 410 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-001-deadlock-prevention.md` | 저장소 문서 | 3,692 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-002-transaction-boundary.md` | 저장소 문서 | 4,376 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-003-timeout-hierarchy.md` | 저장소 문서 | 3,581 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-004-outbox-pattern.md` | 저장소 문서 | 5,041 | 175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-005-circuit-breaker.md` | 저장소 문서 | 4,502 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-006-solid-srp.md` | 저장소 문서 | 5,538 | 171 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-007-dry-duplication.md` | 저장소 문서 | 9,558 | 292 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/GR-REFACTOR-008-environment-variable-naming.md` | 저장소 문서 | 4,275 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/buffer-pattern-duplication.md` | 저장소 문서 | 3,095 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/cache-service-duplication.md` | 저장소 문서 | 4,194 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/circular-lock-deadlock.md` | 저장소 문서 | 2,805 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/completablefuture-exception-duplication.md` | 저장소 문서 | 3,136 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/connection-vampire.md` | 저장소 문서 | 2,686 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/controller-response-duplication.md` | 저장소 문서 | 2,457 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/cube-decorator-duplication.md` | 저장소 문서 | 3,156 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/data-masking-duplication.md` | 저장소 문서 | 2,249 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/mdl-freeze.md` | 저장소 문서 | 1,655 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/stateful-inmemory-buffer.md` | 저장소 문서 | 2,864 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/stateful-static-counter.md` | 저장소 문서 | 2,688 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/stateful-threadlocal.md` | 저장소 문서 | 2,619 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/taskcontext-duplication.md` | 저장소 문서 | 2,067 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/timeout-configuration-duplication.md` | 저장소 문서 | 2,621 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/timeout-hierarchy.md` | 저장소 문서 | 2,040 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/refactor/transactional-outbox-pattern.md` | 저장소 문서 | 2,644 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/service-modules.md` | 저장소 문서 | 15,025 | 502 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/solid-ddd.md` | 저장소 문서 | 29,526 | 912 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/stateless.md` | 저장소 문서 | 7,984 | 285 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/architecture/system-design.md` | 저장소 문서 | 9,219 | 332 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/INDEX.md` | 저장소 문서 | 3,243 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/INDEX.md` | 저장소 문서 | 4,287 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/cache-key-design.md` | 저장소 문서 | 8,565 | 308 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/double-check-pattern.md` | 저장소 문서 | 8,899 | 293 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/follower-timeout-retry.md` | 저장소 문서 | 10,463 | 333 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/l1-backfill.md` | 저장소 문서 | 9,249 | 361 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/probabilistic-early-recomputation.md` | 저장소 문서 | 8,102 | 261 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/tiered-cache-singleflight.md` | 저장소 문서 | 8,936 | 313 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/tiered-cache.md` | 저장소 문서 | 3,497 | 133 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/cache/two-phase-snapshot.md` | 저장소 문서 | 7,554 | 221 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/INDEX.md` | 저장소 문서 | 2,018 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/async-patterns.md` | 저장소 문서 | 5,288 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/deadlock-prevention.md` | 저장소 문서 | 8,491 | 262 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/lock-strategy.md` | 저장소 문서 | 8,194 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/race-condition.md` | 저장소 문서 | 8,528 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/skip-locked.md` | 저장소 문서 | 8,959 | 247 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/thread-pool.md` | 저장소 문서 | 6,772 | 183 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/concurrency/virtual-threads.md` | 저장소 문서 | 5,440 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/performance/INDEX.md` | 성능·부하 문서 | 1,293 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/performance/thread-pool-tuning.md` | 성능·부하 문서 | 5,063 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/INDEX.md` | 저장소 문서 | 3,762 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/auto-warmup-strategy.md` | 저장소 문서 | 12,366 | 433 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/circuit-breaker.md` | 저장소 문서 | 17,202 | 502 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/distributed-lock-scheduler.md` | 저장소 문서 | 14,995 | 427 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/fallback.md` | 저장소 문서 | 9,652 | 264 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/resilience/marker-interface.md` | 저장소 문서 | 16,725 | 505 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/INDEX.md` | 저장소 문서 | 5,654 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/aop-facade.md` | 저장소 문서 | 7,238 | 250 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/exception-handling.md` | 저장소 문서 | 16,572 | 496 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/logic-executor.md` | 저장소 문서 | 12,356 | 378 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/optional-chaining.md` | 저장소 문서 | 6,732 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/backend/spring/solid-principles.md` | 저장소 문서 | 8,980 | 306 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/coding-style/imports.md` | 저장소 문서 | 639 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/database/INDEX.md` | 저장소 문서 | 2,044 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/database/connection-pool.md` | 저장소 문서 | 6,229 | 200 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/database/innodb-buffer-pool.md` | 저장소 문서 | 7,251 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/INDEX.md` | 저장소 문서 | 2,255 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/graceful-shutdown-coordination.md` | 저장소 문서 | 10,489 | 393 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/rate-limiting-distributed.md` | 저장소 문서 | 9,859 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/redis.md` | 저장소 문서 | 7,033 | 259 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/resilience-reliiability.md` | 저장소 문서 | 13,473 | 482 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/scaleout.md` | 저장소 문서 | 9,704 | 301 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/infra/scheduler-distributed-lock.md` | 저장소 문서 | 8,860 | 319 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/migration/compiler-centric.md` | 저장소 문서 | 7,308 | 293 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/INDEX.md` | 저장소 문서 | 3,633 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/cors-security.md` | 저장소 문서 | 5,594 | 201 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/csp-content-security-policy.md` | 저장소 문서 | 6,699 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/incident-response-playbook.md` | 저장소 문서 | 10,036 | 352 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/input-validation.md` | 저장소 문서 | 4,883 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/jwt-security.md` | 저장소 문서 | 5,486 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/prometheus-security-filter.md` | 저장소 문서 | 9,901 | 323 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/secrets-management.md` | 저장소 문서 | 5,646 | 220 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/sensitive-data-logging.md` | 저장소 문서 | 4,615 | 157 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/spring-security-filter.md` | 저장소 문서 | 6,029 | 188 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/security/token-reuse-detection.md` | 저장소 문서 | 8,775 | 300 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/INDEX.md` | 저장소 문서 | 9,588 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos-engineering.md` | 저장소 문서 | 12,210 | 349 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N01-singleflight-race-condition.md` | 저장소 문서 | 1,956 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N02-deadlock-trap.md` | 저장소 문서 | 2,271 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N03-thread-pool-exhaustion.md` | 저장소 문서 | 2,574 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N04-connection-vampire.md` | 저장소 문서 | 3,464 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N05-celebrity-problem.md` | 저장소 문서 | 2,439 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N06-timeout-cascade.md` | 저장소 문서 | 2,707 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N07-metadata-lock-freeze.md` | 저장소 문서 | 1,877 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N08-redis-death-thundering-herd.md` | 저장소 문서 | 2,236 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N09-circular-lock-deadlock.md` | 저장소 문서 | 1,844 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N10-caller-runs-policy.md` | 저장소 문서 | 3,811 | 131 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N11-lock-fallback-avalanche.md` | 저장소 문서 | 3,597 | 131 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N12-async-context-loss.md` | 저장소 문서 | 4,088 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N13-zombie-outbox.md` | 저장소 문서 | 3,592 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N14-pipeline-exception.md` | 저장소 문서 | 3,416 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N15-aop-order-problem.md` | 저장소 문서 | 3,213 | 118 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N16-self-invocation.md` | 저장소 문서 | 3,364 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N17-poison-pill.md` | 저장소 문서 | 4,287 | 164 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N18-deep-paging.md` | 저장소 문서 | 3,911 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/chaos/N19-outbox-replay.md` | 저장소 문서 | 4,887 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/concurrency-test.md` | 저장소 문서 | 5,702 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/flaky-test-prevention.md` | 저장소 문서 | 8,347 | 270 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/load-test-strategy.md` | 성능·부하 문서 | 11,955 | 449 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/nightmare-tests.md` | 저장소 문서 | 25,147 | 988 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/testcontainers-singleton.md` | 저장소 문서 | 10,984 | 373 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/16_Guardrails/testing/unit-test.md` | 저장소 문서 | 6,608 | 243 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/17_Metrics/verification-strategy.md` | 저장소 문서 | 18,763 | 678 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/18_Portfolio/cover-letter-toss-platform.md` | 저장소 문서 | 5,644 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/18_Portfolio/external-api-pipeline-evolution.md` | 2차 서술 | 11,781 | 412 | 수동 심층 검토+교차검증 |
| `docs/18_Portfolio/like-refactoring-portfolio.md` | 저장소 문서 | 53,626 | 767 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/18_Portfolio/performance-optimization-portfolio-v2.md` | 2차 서술 | 35,442 | 763 | 수동 심층 검토+교차검증 |
| `docs/18_Portfolio/performance-optimization-portfolio.md` | 성능·부하 문서 | 58,827 | 779 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/18_Portfolio/portfolio_example.md` | 저장소 문서 | 17,639 | 588 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/18_Portfolio/required_portfolio.md` | 제외 | 26,393 | 666 | 수동 심층 검토+교차검증 |
| `docs/18_Portfolio/resume-toss-platform.md` | 저장소 문서 | 6,448 | 173 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/19_Rules/dto-ownership.md` | 저장소 문서 | 8,147 | 287 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/20_Rollback/README.md` | 저장소 문서 | 3,968 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/20_Rollback/strategy.md` | 저장소 문서 | 23,636 | 899 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/CREDENTIAL_ROTATION_PROCEDURES.md` | 저장소 문서 | 28,898 | 855 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/DLQ_RETENTION_POLICY.md` | 저장소 문서 | 12,046 | 442 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/ON_CALL_CHECKLIST.md` | 저장소 문서 | 15,812 | 557 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/adoption.md` | 저장소 문서 | 16,184 | 459 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/coolify-setup-guide.md` | 저장소 문서 | 4,120 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/dag-migration.md` | 저장소 문서 | 4,094 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/docker-deploy-runbook.md` | 저장소 문서 | 6,785 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/21_Operations/read-replica-setup-guide.md` | 저장소 문서 | 3,344 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/README.md` | 저장소 문서 | 3,007 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/appendix-a-metrics.md` | 저장소 문서 | 2,431 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/appendix-b-timeline.md` | 저장소 문서 | 4,891 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/appendix-c-adr-index.md` | 저장소 문서 | 3,185 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/appendix-d-issues-prs.md` | 저장소 문서 | 5,291 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-1-genesis.md` | 저장소 문서 | 8,841 | 248 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-2-redis-atomicity.md` | 저장소 문서 | 9,450 | 258 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-3-scaleout.md` | 저장소 문서 | 12,054 | 325 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-4-multimodule.md` | 저장소 문서 | 5,777 | 183 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-5-hexagonal.md` | 저장소 문서 | 7,725 | 185 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-6-postgresql.md` | 저장소 문서 | 9,405 | 244 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/22_Like_Refactoring_Journey/chapter-7-direct-db.md` | 저장소 문서 | 14,361 | 353 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/00_prologue.md` | 저장소 문서 | 5,216 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/01_cascade_failure.md` | 저장소 문서 | 6,414 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/02_circuit_breaker.md` | 저장소 문서 | 10,886 | 312 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/03_connection_pool.md` | 저장소 문서 | 7,706 | 229 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/04_cache_stampede.md` | 저장소 문서 | 8,126 | 258 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/05_virtual_thread.md` | 저장소 문서 | 7,502 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/06_advisory_lock.md` | 저장소 문서 | 7,674 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/07_alert_silence.md` | 저장소 문서 | 6,428 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/08_like_domain.md` | 저장소 문서 | 7,341 | 247 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/09_great_migration.md` | 저장소 문서 | 7,740 | 279 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/10_test_strategy.md` | 저장소 문서 | 6,936 | 205 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/11_zero_trycatch.md` | 저장소 문서 | 6,904 | 230 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/12_7347_rps.md` | 저장소 문서 | 7,146 | 225 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md` | 저장소 문서 | 25,077 | 484 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/README.md` | 저장소 문서 | 5,539 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/23_Incident_Response_Journey/epilogue.md` | 저장소 문서 | 4,117 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/00_index.md` | 저장소 문서 | 5,654 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/01_async_concurrency.md` | 저장소 문서 | 7,827 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/02_memory_streaming.md` | 저장소 문서 | 7,954 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/03_pipeline_data_correctness.md` | 저장소 문서 | 9,576 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/04_orchestration_airflow.md` | 저장소 문서 | 8,581 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/05_loop_lifecycle_observability.md` | 저장소 문서 | 5,810 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/06_infra_deploy_migration.md` | 저장소 문서 | 7,734 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/24_Troubleshooting_Casebook/07_parquet_poc_benchmark.md` | 성능·부하 문서 | 1,620 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/CODE_REVIEW_CHECKLIST_Flaky_Test_Fix.md` | 저장소 문서 | 18,295 | 457 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/Chaos_Report_Template.md` | 저장소 문서 | 18,268 | 622 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/DEMO_SCRIPT.md` | 저장소 문서 | 19,799 | 469 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/DOCUMENTATION_INTEGRITY_CHECKLIST.md` | 저장소 문서 | 15,051 | 378 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/GITHUB_SECRETS_SETUP_GUIDE.md` | 저장소 문서 | 12,171 | 343 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/ISSUE_TEMPLATE.md` | 저장소 문서 | 11,457 | 332 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/PR_TEMPLATE.md` | 저장소 문서 | 10,481 | 284 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/98_Templates/README.md` | 저장소 문서 | 9,193 | 215 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/00_프롤로그_시스템_개요.md` | 저장소 문서 | 26,512 | 802 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/01_API_설계.md` | 저장소 문서 | 30,614 | 1014 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/02_도메인_모델링.md` | 저장소 문서 | 34,464 | 1100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/03_아키텍처_진화.md` | 저장소 문서 | 52,661 | 1333 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/04_트랜잭션과_정합성.md` | 저장소 문서 | 39,483 | 1016 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/05_성능_엔지니어링.md` | 저장소 문서 | 34,677 | 1101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/06_테스트_전략.md` | 저장소 문서 | 28,846 | 925 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/07_관측성과_운영.md` | 저장소 문서 | 33,533 | 1073 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/08_보안과_안전장치.md` | 저장소 문서 | 33,713 | 1060 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/09_한계와_다음단계.md` | 저장소 문서 | 21,959 | 713 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/Portfolio_Book/README.md` | 저장소 문서 | 4,271 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/README.md` | 저장소 문서 | 1,948 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/01-redis-death.md` | 저장소 문서 | 49,928 | 1198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/ADR-006-redis-lock-lease-timeout-ha.md` | 저장소 문서 | 12,065 | 348 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/ADR-050-redis-7.0-redisson-3.48.0-adoption.md` | 저장소 문서 | 16,052 | 493 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/ADR-310-redis-lock-migration.md` | 저장소 문서 | 16,633 | 459 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/N08-thundering-herd-redis-death.md` | 저장소 문서 | 7,567 | 254 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/redis-failover-topology.md` | 저장소 문서 | 11,894 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/redis-ha-architecture.md` | 저장소 문서 | 16,487 | 448 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/redis-sentinel-readmode.md` | 저장소 문서 | 10,303 | 273 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/_archive/redis-deprecated/redis-zset-ttl.md` | 저장소 문서 | 10,124 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/ai-traces/.gitignore` | 저장소 문서 | 25 | 3 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/architecture.md` | 설계 | 14,460 | 240 | 수동 심층 검토+교차검증 |
| `docs/endurance-test/endurance-report-71h.md` | 측정 | 11,632 | 217 | 수동 심층 검토+교차검증 |
| `docs/endurance-test/endurance-report-82h.md` | 측정 | 6,826 | 161 | 수동 심층 검토+교차검증 |
| `docs/engineering-archive-kafka-pipeline.md` | 설계·측정 | 22,442 | 564 | 수동 심층 검토+교차검증 |
| `docs/images/Sequence Diagram_ Character Equipment.png` | 저장소 문서 | 189,425 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/images/locust_chart_260104.png` | 저장소 문서 | 83,976 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/images/locust_statistics_260104.png` | 저장소 문서 | 29,477 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/incident-history.md` | 저장소 문서 | 7,696 | 187 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/metrics-summary.md` | 저장소 문서 | 7,405 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/operations.md` | 저장소 문서 | 6,946 | 243 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/reports/vs3-validation-2026-06-10T05-04-14.json` | 저장소 문서 | 4,335 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/reports/vs3-validation-2026-06-10T05-04-14.md` | 저장소 문서 | 6,303 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/reports/vs3-validation-TEMPLATE.md` | 저장소 문서 | 1,411 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-04-20-pgmq-pipeline.md` | 저장소 문서 | 21,227 | 608 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-04-28-mq-abstraction-layer.md` | 저장소 문서 | 40,534 | 1038 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-04-28-write-path-pure-calculate.md` | 저장소 문서 | 35,207 | 826 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-04-28-write-path-snapshot-calculator.md` | 저장소 문서 | 67,587 | 1813 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-04-29-read-path-boundary-cleanup.md` | 저장소 문서 | 5,842 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-12-artifact-retention.md` | 저장소 문서 | 47,203 | 1422 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-13-elasticsearch-logging-pipeline.md` | 저장소 문서 | 23,404 | 765 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-15-v6-read-path-phase1.md` | 저장소 문서 | 39,627 | 1204 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-16-v6-read-path-phase2-userign.md` | 저장소 문서 | 36,252 | 1009 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-16-v6-urgent-kafka-pipeline.md` | 저장소 문서 | 40,164 | 1046 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-18-chunk-execution-foundation.md` | 저장소 문서 | 14,918 | 543 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-20-ocid-jsonl-kafka-synchronizer.md` | 저장소 문서 | 36,704 | 977 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-20-ocid-lookup-from-ranking-gzip.md` | 저장소 문서 | 29,038 | 722 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-20-ranking-fetch-pipeline.md` | 저장소 문서 | 23,244 | 578 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-22-v6-redis-like.md` | 저장소 문서 | 30,095 | 852 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-27-auth-decoupling.md` | 저장소 문서 | 16,344 | 426 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-27-module-auth-login.md` | 저장소 문서 | 46,470 | 1318 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-05-29-airflow-scheduler-migration.md` | 저장소 문서 | 26,400 | 763 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-03-concurrency-fixes.md` | 저장소 문서 | 17,526 | 451 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-03-infra-reliability-fixes.md` | 저장소 문서 | 21,794 | 602 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-03-synchronizer-airflow-fixes.md` | 저장소 문서 | 20,739 | 496 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-1019-synchronizer-file-reader-logging.md` | 저장소 문서 | 7,983 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-1077-ocid-mapping-repository-split.md` | 저장소 문서 | 13,197 | 371 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-1100-1111-kafka-fixes.md` | 저장소 문서 | 20,595 | 525 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-1112-cf-chain-pipeline.md` | 저장소 문서 | 25,379 | 607 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-1138-ocid-user-ign-resolver-logging.md` | 저장소 문서 | 4,007 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-984-recursive-cf-to-suspend.md` | 저장소 문서 | 41,681 | 981 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-externalapi-null-to-exception.md` | 저장소 문서 | 17,124 | 507 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-issue-1001-scheduler-failure-propagation-plan.md` | 저장소 문서 | 15,685 | 378 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-issue-1018-null-ocid-observability.md` | 저장소 문서 | 10,216 | 272 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-issue-996-synchronizer-file-reader-errors.md` | 저장소 문서 | 31,291 | 816 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-04-issue-998-scheduler-lock-timeout.md` | 저장소 문서 | 15,578 | 374 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-1106-1107-db-access-pattern-fixes.md` | 저장소 문서 | 13,881 | 419 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-897-port-audit.md` | 저장소 문서 | 13,058 | 341 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-923-processor-decomposition.md` | 저장소 문서 | 18,224 | 518 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-933-synchronizer-repository-sql-decomposition.md` | 저장소 문서 | 18,771 | 469 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-943-dry-module-common-extraction.md` | 저장소 문서 | 11,018 | 268 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-concurrency-adapter.md` | 저장소 문서 | 27,743 | 833 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-issue-896-domain-v2-migration.md` | 저장소 문서 | 33,972 | 889 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-outbox-relocate.md` | 저장소 문서 | 17,557 | 462 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-port-abstraction-cleanup.md` | 저장소 문서 | 26,605 | 849 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-05-tx-hygiene.md` | 저장소 문서 | 27,128 | 731 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1061-sink-event-publisher.md` | 저장소 문서 | 6,073 | 160 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1066-synchronizer-metrics-split.md` | 저장소 문서 | 17,081 | 423 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1069-calc-engine-autoconfig.md` | 저장소 문서 | 11,656 | 305 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1071-remove-dead-dependencies.md` | 저장소 문서 | 8,020 | 194 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1073-calculation-dispatch-extraction.md` | 저장소 문서 | 32,392 | 722 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1074-batch-resolver-extraction.md` | 저장소 문서 | 5,038 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1080-snapshot-chunk-processor-parser.md` | 저장소 문서 | 10,457 | 314 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1081-readmodel-query-decomposition.md` | 저장소 문서 | 21,131 | 518 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1082-batch-scheduler-orchestration.md` | 저장소 문서 | 8,256 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1083-popular-character-service-redis.md` | 저장소 문서 | 8,258 | 214 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1084-urgent-consumer-extraction.md` | 저장소 문서 | 8,395 | 226 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1086-rule-based-analyzer.md` | 저장소 문서 | 6,726 | 184 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1087-ext-api-phase-parsers.md` | 저장소 문서 | 9,045 | 254 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-1088-flat-consumer-decomposition.md` | 저장소 문서 | 32,053 | 767 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-961-instant-clock-migration.md` | 저장소 문서 | 8,593 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-966-scheduler-phase-utils-decomposition.md` | 저장소 문서 | 27,962 | 712 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-986-snapshot-fetch-phase-split.md` | 저장소 문서 | 9,271 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-988-snapshot-chunk-decomposition.md` | 저장소 문서 | 31,068 | 813 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-991-item-equipment-continuous-loop.md` | 저장소 문서 | 5,799 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-batch-read-orchestration-extraction.md` | 저장소 문서 | 69,909 | 1724 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-chunk-consumer-template-cleanup.md` | 저장소 문서 | 13,747 | 320 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-chunk-consumer-template-state-machine.md` | 저장소 문서 | 20,306 | 580 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-chunk-execution-status-sealed.md` | 저장소 문서 | 24,120 | 646 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-chunk-stage-ports-pr1.md` | 저장소 문서 | 12,267 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-like-port-merge.md` | 저장소 문서 | 14,610 | 333 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-replace-magic-numbers.md` | 저장소 문서 | 20,216 | 456 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-06-urgent-read-state-sealed.md` | 저장소 문서 | 13,592 | 354 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-1057-1062-batch-progress-sink-factory.md` | 저장소 문서 | 28,704 | 659 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-1057-batch-progress-and-sink-factory.md` | 저장소 문서 | 32,956 | 846 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-1085-orchestrator-extraction.md` | 저장소 문서 | 32,147 | 810 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-989-chunk-file-manager-extraction.md` | 저장소 문서 | 26,340 | 685 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-990-chunk-pipeline-orchestrator.md` | 저장소 문서 | 24,987 | 594 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-cleanup-airflow-port.md` | 저장소 문서 | 48,984 | 1347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-snapshot-sink-event-publisher.md` | 저장소 문서 | 30,719 | 764 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-07-synchronizer-metrics-domain-split.md` | 저장소 문서 | 25,592 | 672 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-08-1126-executor-rename-split.md` | 저장소 문서 | 39,015 | 1074 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-08-1129-synchronizer-cpu-offload.md` | 저장소 문서 | 13,345 | 372 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-08-1130-rest-controller-io-cpu-split.md` | 저장소 문서 | 7,423 | 194 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-08-io-cpu-split-pattern.md` | 저장소 문서 | 26,804 | 703 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-09-v1-object-storage-foundation-plan.md` | 저장소 문서 | 57,979 | 1572 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-09-v2-pipeline-modules-migration-plan.md` | 저장소 문서 | 67,529 | 1500 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-10-issue-1218-vs3-dev-cutover.md` | 저장소 문서 | 44,888 | 1214 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-10-raw-path-to-minio-migration.md` | 저장소 문서 | 57,667 | 1592 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-12-architecture-review.md` | 저장소 문서 | 20,953 | 534 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-15-minio-operations.md` | 저장소 문서 | 60,563 | 1611 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-ext-api-blocking-fix.md` | 저장소 문서 | 74,395 | 2179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-issue-1289-phase-trigger-endpoint.md` | 저장소 문서 | 71,565 | 1750 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-issue-1290-phase-stop-endpoint.md` | 저장소 문서 | 57,515 | 1541 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-issue-1292-per-phase-dag.md` | 저장소 문서 | 48,319 | 1484 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-lock-async.md` | 저장소 문서 | 33,735 | 907 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-18-pgmq-process-async.md` | 저장소 문서 | 27,884 | 723 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-ext-api-orphan-tmp-cleanup.md` | 저장소 문서 | 24,414 | 641 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-issue-1291-loop-endpoint.md` | 저장소 문서 | 82,258 | 1906 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-issue-1312-streaming-writer-cf-chain.md` | 저장소 문서 | 55,837 | 1381 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-issue-1313-streaming-chunk-parser.md` | 저장소 문서 | 41,222 | 1234 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-issue-1314-direct-buffer-tuning.md` | 저장소 문서 | 12,107 | 285 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md` | 저장소 문서 | 58,383 | 1650 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-offheap-streaming.md` | 저장소 문서 | 45,692 | 1220 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-19-sequence-steps.md` | 저장소 문서 | 30,441 | 827 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase1.md` | 저장소 문서 | 25,620 | 629 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase2.md` | 저장소 문서 | 27,990 | 650 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-22-coolify-self-healing-phase3.md` | 저장소 문서 | 23,882 | 557 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-22-dag-restructure.md` | 저장소 문서 | 94,695 | 2694 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-3am-pipeline-chain.md` | 저장소 문서 | 20,589 | 589 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-analytics-adr-finalization.md` | 저장소 문서 | 6,425 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-class-hierarchy-modeling.md` | 저장소 문서 | 10,732 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-historical-analytics-requirements.md` | 저장소 문서 | 4,348 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-iceberg-feasibility.md` | 저장소 문서 | 13,592 | 286 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-minio-compatibility.md` | 저장소 문서 | 12,316 | 330 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-pg-scalability-assessment.md` | 저장소 문서 | 10,759 | 195 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-query-engine-benchmark.md` | 성능·부하 문서 | 10,240 | 223 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-23-serving-analytics-separation.md` | 저장소 문서 | 12,297 | 195 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-26-airflow-network-reconcile.md` | 저장소 문서 | 12,964 | 390 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-26-nohup-to-docker-deployment.md` | 저장소 문서 | 20,997 | 530 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-06-28-parquet-iceberg-readiness.md` | 저장소 문서 | 37,903 | 1155 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-07-19-etl-runtime-ownership-closure.md` | 저장소 문서 | 41,097 | 764 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-07-19-kafka-delivery-outcome.md` | 저장소 문서 | 49,054 | 748 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-07-19-nexon-access-consolidation.md` | 저장소 문서 | 34,857 | 619 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-07-19-pipeline-artifact-lifecycle.md` | 저장소 문서 | 62,933 | 987 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/2026-07-19-valuation-kernel-extraction.md` | 저장소 문서 | 40,846 | 725 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/plans/v5-cache-miss-call-chain.md` | 저장소 문서 | 37,061 | 1113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-04-19-two-phase-batch-upsert-design.md` | 저장소 문서 | 6,210 | 174 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-04-20-pgmq-pipeline-design.md` | 저장소 문서 | 4,371 | 140 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-04-28-write-path-snapshot-calculator-design.md` | 저장소 문서 | 14,270 | 473 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-05-16-character-basic-sync-design.md` | 저장소 문서 | 1,282 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-05-16-v6-read-model-userign-design.md` | 저장소 문서 | 4,187 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-05-29-airflow-scheduler-migration-design.md` | 저장소 문서 | 4,388 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-1019-synchronizer-file-reader-logging-design.md` | 저장소 문서 | 3,269 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-1077-ocid-mapping-repository-split-design.md` | 저장소 문서 | 7,340 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-1138-ocid-user-ign-resolver-logging-design.md` | 저장소 문서 | 2,938 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-externalapi-null-to-exception-design.md` | 저장소 문서 | 9,329 | 181 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-issue-1001-scheduler-failure-propagation-design.md` | 저장소 문서 | 12,422 | 174 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-issue-1018-null-ocid-design.md` | 저장소 문서 | 5,477 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-issue-996-synchronizer-file-reader-errors-design.md` | 저장소 문서 | 9,504 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-04-issue-998-scheduler-lock-timeout-design.md` | 저장소 문서 | 6,460 | 123 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-1106-1107-db-access-pattern-fixes-design.md` | 저장소 문서 | 6,213 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-897-port-audit-design.md` | 저장소 문서 | 6,776 | 160 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-933-synchronizer-repository-sql-decomposition-design.md` | 저장소 문서 | 5,350 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-943-dry-module-common-extraction-design.md` | 저장소 문서 | 5,360 | 132 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-concurrency-adapter-design.md` | 저장소 문서 | 9,391 | 225 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-outbox-relocate-design.md` | 저장소 문서 | 7,198 | 136 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-05-port-abstraction-cleanup-design.md` | 저장소 문서 | 7,606 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1066-synchronizer-metrics-split-design.md` | 저장소 문서 | 7,547 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1069-calc-engine-autoconfig-design.md` | 저장소 문서 | 7,417 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1073-calculation-dispatch-service-design.md` | 저장소 문서 | 12,402 | 216 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1081-readmodel-query-decomposition-design.md` | 저장소 문서 | 11,644 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1088-flat-consumer-decomposition-design.md` | 저장소 문서 | 10,892 | 201 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1090-synchronizer-infra-extraction-design.md` | 저장소 문서 | 6,295 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-1093-magic-numbers-design.md` | 저장소 문서 | 9,617 | 140 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-907-module-executor-extraction-design.md` | 저장소 문서 | 13,006 | 277 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-966-scheduler-phase-utils-decomposition-design.md` | 저장소 문서 | 13,724 | 268 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-988-snapshot-chunk-processor-decomposition-design.md` | 저장소 문서 | 10,512 | 253 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-batch-read-orchestration-extraction-design.md` | 저장소 문서 | 5,211 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-chunk-consumer-template-state-machine-design.md` | 저장소 문서 | 6,882 | 143 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-chunk-execution-status-sealed-design.md` | 저장소 문서 | 6,053 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md` | 저장소 문서 | 7,011 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-like-port-merge-design.md` | 저장소 문서 | 5,329 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-refactor-batch-1-design.md` | 저장소 문서 | 9,330 | 171 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-06-urgent-read-state-sealed-design.md` | 저장소 문서 | 5,030 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-07-1057-batch-progress-and-sink-factory-design.md` | 저장소 문서 | 8,892 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-07-1085-calculation-job-orchestrator-extraction-design.md` | 저장소 문서 | 8,704 | 146 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-07-989-chunk-file-manager-extraction-design.md` | 저장소 문서 | 10,499 | 222 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-07-snapshot-sink-event-publisher-design.md` | 저장소 문서 | 8,364 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-07-synchronizer-metrics-domain-split-design.md` | 저장소 문서 | 8,717 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1126-executor-rename-split-design.md` | 저장소 문서 | 10,648 | 193 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1127-calculator-worker-split-design.md` | 저장소 문서 | 9,318 | 216 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1128-external-api-cpu-offload-design.md` | 저장소 문서 | 10,064 | 198 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1129-synchronizer-cpu-offload-design.md` | 저장소 문서 | 7,853 | 170 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1130-rest-controller-io-cpu-split-design.md` | 저장소 문서 | 7,333 | 155 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-1131-infra-worker-dispatcher-design.md` | 저장소 문서 | 5,000 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-08-io-cpu-split-pattern-design.md` | 저장소 문서 | 8,312 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-09-minio-storage-migration-design.md` | 저장소 문서 | 24,493 | 447 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-09-v1-object-storage-foundation-design.md` | 저장소 문서 | 21,833 | 474 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-09-v2-pipeline-modules-migration-design.md` | 저장소 문서 | 30,883 | 546 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-10-issue-1218-vs3-dev-cutover-design.md` | 저장소 문서 | 21,401 | 369 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-10-raw-path-to-minio-migration-design.md` | 저장소 문서 | 10,748 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-12-architecture-review-design.md` | 저장소 문서 | 6,235 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-15-minio-operations-design.md` | 저장소 문서 | 17,824 | 289 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-18-ext-api-blocking-fix-design.md` | 저장소 문서 | 14,193 | 341 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-18-issue-1289-phase-trigger-endpoint-design.md` | 저장소 문서 | 9,588 | 193 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-18-issue-1290-phase-stop-endpoint-design.md` | 저장소 문서 | 12,813 | 306 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md` | 저장소 문서 | 17,111 | 395 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-ext-api-orphan-tmp-cleanup-design.md` | 저장소 문서 | 13,058 | 320 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-issue-1291-loop-endpoint-design.md` | 저장소 문서 | 20,399 | 457 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-issue-1312-streaming-writer-cf-chain-design.md` | 저장소 문서 | 19,341 | 351 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-issue-1313-streaming-chunk-parser-design.md` | 저장소 문서 | 18,064 | 357 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-issue-1314-direct-buffer-tuning-design.md` | 저장소 문서 | 11,600 | 261 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-offheap-calculator-cache-design.md` | 저장소 문서 | 17,944 | 337 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` | 저장소 문서 | 15,136 | 322 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-19-sequence-steps-design.md` | 저장소 문서 | 12,290 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-22-coolify-self-healing-design.md` | 저장소 문서 | 21,623 | 358 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-22-dag-restructure-design.md` | 저장소 문서 | 22,413 | 444 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md` | 저장소 문서 | 5,207 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md` | 저장소 문서 | 6,475 | 111 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md` | 저장소 문서 | 12,623 | 254 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-historical-analytics-requirements.md` | 저장소 문서 | 9,634 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-iceberg-feasibility.md` | 저장소 문서 | 7,906 | 168 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-minio-compatibility.md` | 저장소 문서 | 7,325 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md` | 저장소 문서 | 10,986 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-query-engine-benchmark.md` | 성능·부하 문서 | 8,633 | 165 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-23-serving-analytics-separation.md` | 저장소 문서 | 12,437 | 182 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-26-airflow-network-reconcile-design.md` | 저장소 문서 | 5,649 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-26-nohup-to-docker-deployment-design.md` | 저장소 문서 | 6,408 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-28-iceberg-adoption-design.md` | 저장소 문서 | 6,786 | 196 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-06-28-parquet-iceberg-readiness-design.md` | 저장소 문서 | 10,404 | 148 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-etl-infra-deepening-program-design.md` | 저장소 문서 | 13,882 | 204 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-etl-runtime-ownership-closure-design.md` | 저장소 문서 | 10,195 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-kafka-delivery-outcome-design.md` | 저장소 문서 | 16,107 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-nexon-access-consolidation-design.md` | 저장소 문서 | 12,661 | 201 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-pipeline-artifact-lifecycle-design.md` | 저장소 문서 | 16,490 | 245 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `docs/superpowers/specs/2026-07-19-valuation-kernel-extraction-design.md` | 저장소 문서 | 14,510 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `gradle.properties` | 빌드·배포·설정 | 816 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `gradle/libs.versions.toml` | 현재 시스템 | 9,187 | 200 | 수동 심층 검토+교차검증 |
| `gradle/wrapper/gradle-wrapper.jar` | 설정·기타 | 43,764 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `gradle/wrapper/gradle-wrapper.properties` | 빌드·배포·설정 | 250 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `gradlew` | 설정·기타 | 8,733 | 251 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `gradlew.bat` | 설정·기타 | 2,937 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `grafana/dashboard-pipeline-comprehensive.json` | 설정·기타 | 23,746 | 433 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `grafana/dashboard-pipeline.json` | 설정·기타 | 19,470 | 359 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/README.md` | 성능·부하 도구 | 6,864 | 271 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/ab-cycle-test.sh` | 성능·부하 도구 | 6,783 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/analyze-metrics.py` | 성능·부하 도구 | 11,225 | 306 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/extract-sample-users.py` | 성능·부하 도구 | 1,817 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/generate-test-users.py` | 성능·부하 도구 | 1,419 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/data/test-characters.json` | 성능·부하 도구 | 2,408 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/lib/config.js` | 성능·부하 도구 | 3,350 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/lib/helpers.js` | 성능·부하 도구 | 5,178 | 193 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/lib/metrics.js` | 성능·부하 도구 | 5,612 | 255 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/main.js` | 성능·부하 도구 | 9,898 | 314 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/scenarios/mixed-workload.js` | 성능·부하 도구 | 6,509 | 258 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/scenarios/normal-traffic.js` | 성능·부하 도구 | 7,722 | 238 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/scenarios/patch-day.js` | 성능·부하 도구 | 10,388 | 334 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/scenarios/viral-spike.js` | 성능·부하 도구 | 8,753 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/k6/thresholds.js` | 성능·부하 도구 | 5,545 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/load-test-300rps.py` | 성능·부하 도구 | 6,578 | 204 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/load-test.lua` | 성능·부하 도구 | 1,371 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/locustfile.py` | 성능·부하 도구 | 13,715 | 353 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/monitor-rps.py` | 성능·부하 도구 | 1,919 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/nightmare_scenarios.py` | 성능·부하 도구 | 20,626 | 602 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/observability-load-test.py` | 성능·부하 도구 | 15,147 | 438 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/prometheus-queries.md` | 성능·부하 도구 | 4,334 | 232 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/requirements.txt` | 성능·부하 도구 | 107 | 3 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/run-v5-db-throughput.sh` | 성능·부하 도구 | 6,582 | 211 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/scripts/generate-report.sh` | 성능·부하 도구 | 11,222 | 364 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/scripts/run-load-test.sh` | 성능·부하 도구 | 2,693 | 110 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/simple-load-test.py` | 성능·부하 도구 | 4,436 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/tmp/config.json` | 성능·부하 도구 | 754 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-cycle-all-users.lua` | 성능·부하 도구 | 3,852 | 118 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-cycle-test.sh` | 성능·부하 도구 | 915 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-debug.lua` | 성능·부하 도구 | 1,836 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-diagnostic.lua` | 성능·부하 도구 | 3,843 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-expectation-test.lua` | 성능·부하 도구 | 1,332 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-fixed.lua` | 성능·부하 도구 | 3,966 | 125 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-sequential.lua` | 성능·부하 도구 | 5,704 | 171 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-simple.lua` | 성능·부하 도구 | 1,864 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-test-en.lua` | 성능·부하 도구 | 789 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-v4-expectation.lua` | 성능·부하 도구 | 13,496 | 214 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk-v5-expectation.lua` | 성능·부하 도구 | 4,081 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk/acl-benchmark.lua` | 성능·부하 도구 | 2,128 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load-test/wrk_multiple_users.lua` | 성능·부하 도구 | 1,945 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `load_test_v5.py` | 성능·부하 도구 | 9,900 | 292 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/build.gradle` | 빌드·배포·설정 | 8,589 | 275 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/checkpoint.json` | 설정·기타 | 35,671 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/failed.csv` | 설정·기타 | 443,226 | 11264 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/integrationTest/kotlin/maple/expectation/infrastructure/worker/DonationWorkerIntegrationTest.kt` | 테스트 | 9,881 | 294 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/integrationTest/kotlin/maple/expectation/integration/PostgresIntegrationTest.kt` | 테스트 | 12,698 | 367 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/ExpectationApplication.java` | 기타 애플리케이션 코드 | 2,290 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/mapper/EquipmentMapper.java` | 기타 애플리케이션 코드 | 2,259 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/CubeApplicationService.java` | 기타 애플리케이션 코드 | 2,896 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/EquipmentApplicationService.java` | 기타 애플리케이션 코드 | 6,315 | 177 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/FlameApplicationService.java` | 기타 애플리케이션 코드 | 4,854 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/PotentialApplicationService.java` | 기타 애플리케이션 코드 | 4,963 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/StarforceApplicationService.java` | 기타 애플리케이션 코드 | 6,009 | 190 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/calculator/EnhanceDecorator.java` | 기타 애플리케이션 코드 | 530 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/calculator/ExpectationCalculator.java` | 기타 애플리케이션 코드 | 354 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/calculator/ExpectationCalculatorFactory.java` | 기타 애플리케이션 코드 | 1,053 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/calculator/PotentialCalculator.java` | 기타 애플리케이션 코드 | 4,628 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/calculator/impl/BaseItem.java` | 기타 애플리케이션 코드 | 663 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/character/CharacterCreationService.java` | 기타 애플리케이션 코드 | 6,340 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/character/GameCharacterFacade.java` | 기타 애플리케이션 코드 | 5,469 | 131 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/character/GameCharacterService.java` | 기타 애플리케이션 코드 | 9,340 | 232 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/character/OcidResolver.java` | 기타 애플리케이션 코드 | 5,233 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/cube/AbstractCubeDecoratorV2.java` | 기타 애플리케이션 코드 | 5,481 | 204 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/donation/DonationProcessor.java` | 기타 애플리케이션 코드 | 1,664 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/donation/InternalPointPaymentStrategy.java` | 기타 애플리케이션 코드 | 2,607 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/donation/PaymentStrategy.java` | 기타 애플리케이션 코드 | 1,234 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/donation/event/DonationFailedEvent.java` | 기타 애플리케이션 코드 | 157 | 3 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/donation/listener/DonationEventListener.java` | 기타 애플리케이션 코드 | 768 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java` | 기타 애플리케이션 코드 | 18,022 | 417 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/cache/CacheValueConverter.java` | 기타 애플리케이션 코드 | 3,967 | 117 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/cache/CachedResponseBuilder.java` | 기타 애플리케이션 코드 | 1,255 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCompressionService.java` | 기타 애플리케이션 코드 | 3,577 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java` | 기타 애플리케이션 코드 | 16,085 | 387 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/event/CalculationCompletedEvent.java` | 기타 애플리케이션 코드 | 1,915 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/event/CalculationCompletedEventListener.java` | 기타 애플리케이션 코드 | 3,601 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/event/TransactionalEventPublisher.java` | 기타 애플리케이션 코드 | 4,083 | 111 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java` | 기타 애플리케이션 코드 | 13,900 | 401 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/persistence/ExpectationPersistenceService.java` | 기타 애플리케이션 코드 | 3,717 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java` | 기타 애플리케이션 코드 | 5,405 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationTask.java` | 기타 애플리케이션 코드 | 1,762 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/expectation/queue/QueuePriority.java` | 기타 애플리케이션 코드 | 170 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/like/listener/LikeSyncEventListener.java` | 기타 애플리케이션 코드 | 4,600 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/like/metrics/LikeSyncMetricsRecorder.java` | 기타 애플리케이션 코드 | 2,576 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/package-info.java` | 기타 애플리케이션 코드 | 432 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/shutdown/EquipmentPersistenceTracker.java` | 기타 애플리케이션 코드 | 6,288 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/shutdown/ShutdownDataPersistenceService.java` | 기타 애플리케이션 코드 | 12,621 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/service/task/TaskStatusService.java` | 기타 애플리케이션 코드 | 2,213 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/AdminPortAdapter.java` | 기타 애플리케이션 코드 | 2,012 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/AlertPortAdapter.java` | 기타 애플리케이션 코드 | 733 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/CalculationQueuePortAdapter.java` | 기타 애플리케이션 코드 | 1,602 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/DonationPortAdapter.java` | 기타 애플리케이션 코드 | 2,727 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/ExpectationV4PortAdapter.java` | 기타 애플리케이션 코드 | 2,436 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/usecase/GameCharacterPortAdapter.java` | 기타 애플리케이션 코드 | 1,830 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/CalculationStageDelegate.java` | 기타 애플리케이션 코드 | 4,091 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/CharacterAsyncService.java` | 기타 애플리케이션 코드 | 1,768 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/EventUpcaster.java` | 기타 애플리케이션 코드 | 5,858 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/EventUpcasterRegistry.java` | 기타 애플리케이션 코드 | 2,472 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/ExpectationCalculationWorker.java` | 기타 애플리케이션 코드 | 7,119 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/InstrumentedCalculationWorker.java` | 기타 애플리케이션 코드 | 5,256 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/application/worker/PriorityCalculationExecutor.java` | 기타 애플리케이션 코드 | 11,873 | 316 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/AppProperties.java` | 기타 애플리케이션 코드 | 2,547 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/CalculationProperties.java` | 기타 애플리케이션 코드 | 1,060 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/CorePortAdapterConfig.java` | 기타 애플리케이션 코드 | 9,436 | 266 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/DataInitializer.java` | 기타 애플리케이션 코드 | 2,528 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/LookupTableInitializer.java` | 기타 애플리케이션 코드 | 5,645 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/V5Config.java` | 기타 애플리케이션 코드 | 2,390 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/config/V5MetricsConfig.java` | 기타 애플리케이션 코드 | 2,271 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/parser/EquipmentStreamingParser.java` | 기타 애플리케이션 코드 | 21,874 | 599 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/java/maple/expectation/scheduler/ExpectationBatchWriteScheduler.java` | 기타 애플리케이션 코드 | 6,175 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/adapter/BulkLoadPortAdapter.kt` | 기타 애플리케이션 코드 | 1,972 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/adapter/PureCalculationAdapter.kt` | 기타 애플리케이션 코드 | 598 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/admission/AdmissionPortAdapter.kt` | 기타 애플리케이션 코드 | 926 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/dto/BaseDto.kt` | 기타 애플리케이션 코드 | 2,401 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/dto/CharacterEquipmentDto.kt` | 기타 애플리케이션 코드 | 1,647 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/dto/CharacterLikeDto.kt` | 기타 애플리케이션 코드 | 1,648 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/dto/GameCharacterDto.kt` | 기타 애플리케이션 코드 | 2,127 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/service/auth/ApiKeyValidator.kt` | 기타 애플리케이션 코드 | 4,953 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/service/auth/TokenPair.kt` | 기타 애플리케이션 코드 | 778 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/service/calculator/impl/BlackCubeDecorator.kt` | 기타 애플리케이션 코드 | 1,380 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/usecase/ApplicationExecutionPort.kt` | 기타 애플리케이션 코드 | 3,159 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/usecase/AuthPortAdapter.kt` | 기타 애플리케이션 코드 | 3,143 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt` | 기타 애플리케이션 코드 | 4,525 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/kotlin/maple/expectation/scheduler/ExpectationBatchShutdownHandler.kt` | 기타 애플리케이션 코드 | 6,544 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/1m_db_indexing.sql` | 기타 애플리케이션 코드 | 212 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-chaos.yml` | 빌드·배포·설정 | 716 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-ci.yml` | 빌드·배포·설정 | 1,847 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-local.yml` | 빌드·배포·설정 | 6,427 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-pglocal.yml` | 빌드·배포·설정 | 4,488 | 196 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-pgprod.yml` | 빌드·배포·설정 | 1,222 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-prod.yml` | 빌드·배포·설정 | 7,579 | 252 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application-vultr.yml` | 빌드·배포·설정 | 2,737 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/application.yml` | 빌드·배포·설정 | 26,041 | 693 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/data/cube_probability.csv` | 설정·기타 | 22,692,657 | 413803 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/data/userIgn_List.csv` | 설정·기타 | 3,682 | 400 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/db_indexes.sql` | 기타 애플리케이션 코드 | 6,596 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/donation_outbox_schema.sql` | 기타 애플리케이션 코드 | 1,550 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/logback-spring.xml` | 설정·기타 | 4,514 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/nexon_api_outbox_schema.sql` | 기타 애플리케이션 코드 | 1,997 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/nexon_raw_data_schema.sql` | 기타 애플리케이션 코드 | 1,247 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/pessimistic_lock_indexing.sql` | 기타 애플리케이션 코드 | 216 | 2 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/static/basic_character_info.json` | 설정·기타 | 1,536 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/static/evan_equip.json` | 설정·기타 | 357,404 | 12539 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/static/favicon.ico` | 설정·기타 | 0 | 0 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/main/resources/static/mechanic_equip.json` | 설정·기타 | 363,029 | 12795 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/EnvironmentIntegrationTest.java` | 테스트 | 1,242 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/characterization/CalculatorCharacterizationTest.java` | 테스트 | 15,103 | 434 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/characterization/CharacterEquipmentCharacterizationTest.java` | 테스트 | 31,341 | 785 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/controller/AdminControllerTest.java` | 테스트 | 10,403 | 279 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/cache/invalidation/CacheInvalidationIntegrationTest.java` | 테스트 | 10,298 | 279 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/error/GlobalExceptionHandlerTest.java` | 테스트 | 10,839 | 246 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/lock/DualRunLockTest.java` | 테스트 | 7,710 | 247 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/lock/RedisLockConsistencyTest.java` | 테스트 | 11,955 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/ratelimit/RateLimitingFilterIntegrationTest.java` | 테스트 | 6,871 | 170 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/global/resilience/MySQLResilienceIntegrationTest.java` | 테스트 | 11,509 | 324 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/monitoring/AiSreServiceIntegrationTest.java` | 테스트 | 4,792 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/monitoring/MonitoringAlertServiceTest.java` | 테스트 | 3,005 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/CubeServiceTest.java` | 테스트 | 2,956 | 79 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/auth/RefreshTokenIntegrationTest.java` | 테스트 | 18,457 | 466 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/like/LikeSyncCompensationIntegrationTest.java` | 테스트 | 6,824 | 178 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java` | 테스트 | 5,526 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/shutdown/EquipmentPersistenceTrackerTest.java` | 테스트 | 8,325 | 261 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/shutdown/ShutdownDataPersistenceServiceTest.java` | 테스트 | 10,952 | 309 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/service/v4/buffer/ExpectationWriteBackBufferTest.java` | 테스트 | 11,297 | 333 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test-legacy/java/maple/expectation/support/AbstractContainerBaseTest.java` | 테스트 | 2,077 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/ArchTest.java` | 테스트 | 25,823 | 685 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/ModuleDependencyTest.java` | 테스트 | 28,299 | 758 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/PortSignatureRuleTest.java` | 테스트 | 9,820 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/SOLIDPrinciplesTest.java` | 테스트 | 19,089 | 531 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/SpringIsolationTest.java` | 테스트 | 19,472 | 511 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/architecture/StatelessDesignTest.java` | 테스트 | 18,386 | 491 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/CleanArchitectureTest.java` | 테스트 | 5,240 | 152 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/ExpectationApplicationTests.java` | 테스트 | 1,653 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/StatelessAlertServiceIntegrationTest.java` | 테스트 | 16,294 | 382 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/StatelessAlertServiceTest.java` | 테스트 | 3,069 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/channel/AlertTestConfig.java` | 테스트 | 419 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/channel/DiscordAlertChannelIntegrationTest.java` | 테스트 | 18,257 | 404 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/channel/LocalFileAlertChannelTest.java` | 테스트 | 2,245 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/strategy/AlertChannelStrategyTest.java` | 테스트 | 15,005 | 354 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/alert/support/WebClientMockHelper.java` | 테스트 | 4,067 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/aop/ConcurrencyStatsExtension.java` | 테스트 | 1,220 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/aop/context/SkipEquipmentL2CacheContextTest.java` | 테스트 | 10,620 | 317 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/character/Issues637To644E2ETest.java` | 테스트 | 22,944 | 594 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/cube/CubeServiceTest.java` | 테스트 | 12,505 | 341 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/cube/component/CubeComputeBufferTest.java` | 테스트 | 2,049 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/cube/policy/CubeCostPolicyTest.java` | 테스트 | 3,629 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/shutdown/EquipmentPersistenceTrackerTest.java` | 테스트 | 7,305 | 228 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/application/service/shutdown/ShutdownDataPersistenceServiceTest.java` | 테스트 | 8,597 | 251 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/archunit/ArchitectureTest.java` | 테스트 | 16,877 | 439 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/archunit/KotlinJpaEntityTest.java` | 테스트 | 3,522 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/archunit/TransactionManagerBindingTest.java` | 테스트 | 5,982 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/batch/reader/OcidReaderTest.java` | 테스트 | 9,922 | 264 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/config/GlobalTestConfig.java` | 테스트 | 581 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/config/SchedulerConfigTest.java` | 테스트 | 9,161 | 241 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/controller/AdminControllerUnitTest.java` | 테스트 | 9,869 | 267 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/controller/AuthControllerUnitTest.java` | 테스트 | 2,068 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/controller/GameCharacterControllerV1Test.java` | 테스트 | 3,956 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/controller/GameCharacterControllerV4Test.java` | 테스트 | 12,011 | 311 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/domain/v2/CharacterEquipmentTest.java` | 테스트 | 13,110 | 329 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/executor/DefaultCheckedLogicExecutorTest.java` | 테스트 | 14,895 | 406 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/executor/policy/FinallyPolicyTest.java` | 테스트 | 11,582 | 323 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/jdbc/JdbcBatchUpsertRepositoryIntegrationTest.java` | 테스트 | 12,556 | 347 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/queue/strategy/InMemoryBufferStrategyTest.java` | 테스트 | 10,947 | 369 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/security/filter/PrometheusSecurityFilterTest.java` | 테스트 | 11,925 | 380 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/infrastructure/security/jwt/JwtTokenProviderTest.java` | 테스트 | 18,589 | 546 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/monitoring/AiSreServiceTest.java` | 테스트 | 5,663 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/monitoring/MetricsCollectorTest.java` | 테스트 | 5,398 | 164 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/monitoring/MonitoringAlertServiceUnitTest.java` | 테스트 | 7,675 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/monitoring/PiiMaskingFilterTest.java` | 테스트 | 4,814 | 181 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/scheduler/ExpectationBatchWriteSchedulerTest.java` | 테스트 | 7,575 | 213 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/scheduler/PopularCharacterWarmupSchedulerTest.java` | 테스트 | 7,919 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/calculator/PotentialCalculatorTest.java` | 테스트 | 2,691 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/ingestion/BatchWriterTest.java` | 테스트 | 7,863 | 226 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v2/cache/EquipmentCacheServiceTest.java` | 테스트 | 9,958 | 319 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v2/cache/TotalExpectationCacheServiceTest.java` | 테스트 | 6,446 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v2/cube/component/ProbabilityConvolverTest.java` | 테스트 | 13,599 | 363 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v2/cube/component/TailProbabilityCalculatorTest.java` | 테스트 | 7,367 | 265 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v4/EquipmentExpectationServiceV4SingleflightTest.java` | 테스트 | 8,824 | 226 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v4/cache/ExpectationCacheCoordinatorTest.java` | 테스트 | 10,910 | 256 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/CalculationQueuePortAdapterTest.java` | 테스트 | 5,204 | 154 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/ExpectationCalculationQueueTest.java` | 테스트 | 11,230 | 315 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/GameCharacterControllerV5Test.java` | 테스트 | 7,806 | 223 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/TaskStatusServiceTest.java` | 테스트 | 7,017 | 229 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/V5TestConfiguration.java` | 테스트 | 494 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/service/v5/event/ViewTransformerTest.java` | 테스트 | 6,386 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/AbstractContainerBaseTest.java` | 테스트 | 2,053 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/AppIntegrationTestSupport.java` | 테스트 | 3,863 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/EnableTimeLogging.java` | 테스트 | 832 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/IntegrationTestSupport.java` | 테스트 | 783 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/SharedContainers.java` | 테스트 | 1,458 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/TestLogicExecutors.java` | 테스트 | 8,647 | 231 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/support/TestLogicExecutorsTest.java` | 테스트 | 4,099 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/test/PostgresContainerBaseTest.java` | 테스트 | 2,658 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/test/PostgresContainerTest.java` | 테스트 | 922 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/util/StatParserTest.java` | 테스트 | 1,364 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/util/StatTypeTestNew.java` | 테스트 | 8,929 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/java/maple/expectation/util/converter/GzipStringConverterTest.java` | 테스트 | 518 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/application/service/auth/ApiKeyValidatorTest.kt` | 테스트 | 4,513 | 114 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculatorTest.kt` | 테스트 | 4,110 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/config/DatabaseCleaner.kt` | 테스트 | 3,136 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/config/TestcontainersConfiguration.kt` | 테스트 | 2,701 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategyTest.kt` | 테스트 | 19,004 | 539 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/infrastructure/security/jwt/JwtAlgorithmSecurityVerificationTest.kt` | 테스트 | 2,664 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/AdmissionControlIntegrationTest.kt` | 테스트 | 16,954 | 405 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/EndToEndAdmissionControlTest.kt` | 테스트 | 23,007 | 556 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/cache/MultiInstanceCacheInvalidationTest.kt` | 테스트 | 15,628 | 393 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/postgres/PostgresIntegrationTest.kt` | 테스트 | 12,612 | 362 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/repository/CharacterEquipmentRepositoryIntegrationTest.kt` | 테스트 | 10,716 | 282 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/repository/CharacterLikeRepositoryIntegrationTest.kt` | 테스트 | 16,399 | 432 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/repository/GameCharacterRepositoryIntegrationTest.kt` | 테스트 | 11,873 | 311 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/integration/repository/MemberRepositoryIntegrationTest.kt` | 테스트 | 10,128 | 290 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/smoke/P0CharacterSmokeTest.kt` | 테스트 | 1,359 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/smoke/P0ExpectationSmokeTest.kt` | 테스트 | 2,056 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/smoke/P0HealthSmokeTest.kt` | 테스트 | 1,799 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/smoke/SmokeTestBase.kt` | 테스트 | 3,881 | 110 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/smoke/SmokeTestScenarios.md` | 테스트 | 7,506 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/support/IntegrationTestBase.kt` | 테스트 | 4,671 | 143 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/ApiIntegrationTestBase.kt` | 테스트 | 6,650 | 217 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/DatabaseCleaner.kt` | 테스트 | 3,659 | 101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/IntegrationTestBase.kt` | 테스트 | 5,729 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/RepositoryIntegrationTestBase.kt` | 테스트 | 3,596 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/service/ServiceTestTemplate.kt` | 테스트 | 8,105 | 275 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/service/example/FlameTrialsServiceExampleTest.kt` | 테스트 | 6,391 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/usecase/README.md` | 테스트 | 6,794 | 252 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/usecase/UsecaseTestTemplate.kt` | 테스트 | 6,694 | 227 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/test/usecase/example/RateLimitingFacadeExampleTest.kt` | 테스트 | 5,390 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testfixtures/ApiTestUtilities.kt` | 테스트 | 2,364 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testfixtures/DomainFixtures.kt` | 테스트 | 1,554 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testfixtures/Fixtures.kt` | 테스트 | 8,445 | 225 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/AdvisoryLockConcurrencyTest.kt` | 테스트 | 2,948 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/ContainerSingletonTest.kt` | 테스트 | 1,513 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/ContextCachingTest.kt` | 테스트 | 1,685 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/DatabaseIsolationTest.kt` | 테스트 | 3,180 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/PgmqCompetitiveConsumerTest.kt` | 테스트 | 13,427 | 317 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/PgmqIsolationTest.kt` | 테스트 | 3,144 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/PgmqTestSupport.kt` | 테스트 | 4,682 | 167 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/PgmqWorkerTestBase.kt` | 테스트 | 7,355 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/PgmqWorkerTestBaseExample.kt` | 테스트 | 3,091 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/SpringContextCounter.kt` | 테스트 | 861 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/TestInfraPerformanceReport.kt` | 테스트 | 2,207 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqClientIntegrationTest.kt` | 테스트 | 26,316 | 789 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqTestSupport.kt` | 테스트 | 3,961 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqTransactionAtomicityTest.kt` | 테스트 | 4,505 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/META-INF/spring/org.springframework.boot.test.context.TestConfiguration.imports` | 테스트 | 53 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/application-pgmq-test.yml` | 테스트 | 1,165 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/application-pgtest.yml` | 테스트 | 3,223 | 136 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/application-test.yml` | 테스트 | 4,074 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/application.yml` | 테스트 | 3,628 | 154 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/docker-java.properties` | 테스트 | 17 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/evan_equip.json` | 테스트 | 357,404 | 12539 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/junit-platform.properties` | 테스트 | 803 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/mechanic_equip.json` | 테스트 | 363,029 | 12795 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/sql/init-pgmq.sql` | 테스트 | 232 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-app/src/test/resources/testcontainers.properties` | 테스트 | 63 | 2 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/build.gradle` | 빌드·배포·설정 | 980 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/fingerprint/FingerprintService.kt` | 기타 애플리케이션 코드 | 523 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/jwt/JwtGeneratorService.kt` | 기타 애플리케이션 코드 | 1,677 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/kafka/AuthEventPublisher.kt` | 기타 애플리케이션 코드 | 1,204 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/kafka/AuthResponseConsumer.kt` | 기타 애플리케이션 코드 | 1,419 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/kafka/PendingLoginRegistry.kt` | 기타 애플리케이션 코드 | 1,347 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/login/LoginResult.kt` | 기타 애플리케이션 코드 | 210 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/login/LoginService.kt` | 기타 애플리케이션 코드 | 3,665 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/kotlin/maple/auth/session/SessionCacheService.kt` | 기타 애플리케이션 코드 | 1,427 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/main/resources/application.yml` | 빌드·배포·설정 | 506 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/test/kotlin/maple/auth/kafka/PendingLoginRegistryTest.kt` | 테스트 | 2,037 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-auth/src/test/kotlin/maple/auth/login/LoginServiceTest.kt` | 테스트 | 5,215 | 136 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/build.gradle` | 빌드·배포·설정 | 1,898 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt` | 현재 파이프라인 코드 | 1,331 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` | 현재 코드 | 10,758 | 224 | 수동 심층 검토+교차검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt` | 현재 파이프라인 코드 | 2,484 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt` | 현재 파이프라인 코드 | 522 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CacheStats.kt` | 현재 파이프라인 코드 | 451 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt` | 현재 파이프라인 코드 | 1,706 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt` | 현재 파이프라인 코드 | 1,040 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapSerializedBackend.kt` | 현재 파이프라인 코드 | 4,885 | 132 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt` | 현재 파이프라인 코드 | 1,710 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt` | 현재 파이프라인 코드 | 832 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/ChunkParserConfig.kt` | 현재 파이프라인 코드 | 436 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/CoroutineDispatcherConverter.kt` | 현재 파이프라인 코드 | 1,293 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/CoroutineDispatchers.kt` | 현재 파이프라인 코드 | 1,895 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/ExternalApiRunStatusProperties.kt` | 현재 파이프라인 코드 | 503 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/config/PipelineProperties.kt` | 현재 파이프라인 코드 | 2,057 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt` | 현재 파이프라인 코드 | 1,825 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt` | 현재 코드 | 3,092 | 74 | 수동 심층 검토+교차검증 |
| `module-calculator/src/main/kotlin/maple/calculator/event/ChunkProcessingEvent.kt` | 현재 파이프라인 코드 | 850 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/event/KafkaResultEventPublisher.kt` | 현재 파이프라인 코드 | 1,283 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt` | 현재 파이프라인 코드 | 2,119 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/CalculatorMetrics.kt` | 현재 파이프라인 코드 | 2,911 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/CalculatorMetricsListener.kt` | 현재 파이프라인 코드 | 1,824 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/CalculatorVolumeMetrics.kt` | 현재 파이프라인 코드 | 2,820 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/ChunkParserMetrics.kt` | 현재 파이프라인 코드 | 873 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/model/CalculationResult.kt` | 현재 파이프라인 코드 | 613 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/model/ChunkResult.kt` | 현재 파이프라인 코드 | 326 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/parser/FlatItem.kt` | 현재 파이프라인 코드 | 182 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotChunkParser.kt` | 현재 파이프라인 코드 | 952 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotEquipmentParser.kt` | 현재 파이프라인 코드 | 3,003 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotEventParser.kt` | 현재 파이프라인 코드 | 587 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/pipeline/SnapshotChunkPipeline.kt` | 현재 파이프라인 코드 | 3,565 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt` | 현재 파이프라인 코드 | 2,712 | 74 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/processor/EquipmentCalculationInputConverter.kt` | 현재 파이프라인 코드 | 2,845 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SampleLogSerializer.kt` | 현재 파이프라인 코드 | 837 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | 현재 코드 | 9,800 | 228 | 수동 심층 검토+교차검증 |
| `module-calculator/src/main/kotlin/maple/calculator/reader/GzipJsonlSnapshotRecordReader.kt` | 현재 파이프라인 코드 | 1,731 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/runstate/CalculatorCurrentRunIdHolder.kt` | 현재 파이프라인 코드 | 5,153 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | 현재 코드 | 6,593 | 152 | 수동 심층 검토+교차검증 |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CountingOutputStream.kt` | 현재 파이프라인 코드 | 897 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/kotlin/maple/calculator/writer/WriteCounters.kt` | 현재 파이프라인 코드 | 591 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/resources/application-prod.yml` | 현재 파이프라인 코드 | 85 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/resources/application.yml` | 현재 파이프라인 코드 | 3,406 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/main/resources/logback-spring.xml` | 현재 파이프라인 코드 | 2,654 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt` | 테스트 | 16,754 | 375 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendFactoryTest.kt` | 테스트 | 1,613 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/cache/CaffeineCacheBackendTest.kt` | 테스트 | 1,574 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/cache/OffHeapSerializedBackendTest.kt` | 테스트 | 4,296 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumerTest.kt` | 테스트 | 2,254 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/parser/SnapshotChunkParserTest.kt` | 테스트 | 3,520 | 88 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/pipeline/SnapshotChunkPipelineTest.kt` | 테스트 | 4,130 | 111 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/processor/EquipmentCalculationInputConverterTest.kt` | 테스트 | 11,207 | 301 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt` | 테스트 | 4,634 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/writer/CountingOutputStreamTest.kt` | 테스트 | 1,616 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/writer/StubObjectStorage.kt` | 테스트 | 2,526 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-calculator/src/test/kotlin/maple/calculator/writer/WriteCountersTest.kt` | 테스트 | 1,014 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/build.gradle` | 빌드·배포·설정 | 9,258 | 293 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/logs/trace.log` | 설정·기타 | 0 | 0 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/circuitbreaker/CircuitBreakerClosedToOpenChaosTest.java` | 테스트 | 3,328 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/circuitbreaker/CircuitBreakerHalfOpenToClosedChaosTest.java` | 테스트 | 2,927 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/circuitbreaker/CircuitBreakerHalfOpenToOpenChaosTest.java` | 테스트 | 4,063 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/circuitbreaker/CircuitBreakerOpenToHalfOpenChaosTest.java` | 테스트 | 3,229 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/core/OOMChaosTest.java` | 테스트 | 5,504 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/PostgresConnectionTimeoutChaosTest.java` | 테스트 | 3,335 | 101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/PostgresHighLatencyChaosTest.java` | 테스트 | 5,179 | 155 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/PostgresNetworkPartitionChaosTest.java` | 테스트 | 3,677 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/queue/PgmqPartialFailureChaosTest.java` | 테스트 | 4,718 | 140 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/queue/PgmqQueueTimeoutChaosTest.java` | 테스트 | 4,647 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/queue/PgmqQueueUnavailableChaosTest.java` | 테스트 | 2,961 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/DiskFullChaosTest.java` | 테스트 | 6,516 | 213 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/GcPauseChaosTest.java` | 테스트 | 6,264 | 201 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/PoolExhaustionChaosTest.java` | 테스트 | 5,748 | 195 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/config/ChaosTestConfig.java` | 설정·기타 | 935 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/infrastructure/external/MockNexonApiClient.java` | 설정·기타 | 1,922 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/support/AbstractContainerBaseTest.java` | 테스트 | 3,520 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/support/ChaosTestSupport.java` | 설정·기타 | 656 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/support/ContainerManager.java` | 설정·기타 | 1,940 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/java/maple/expectation/support/SentinelContainerBase.java` | 설정·기타 | 3,200 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/resources/application-chaos.yml` | 빌드·배포·설정 | 4,161 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/resources/junit-platform.properties` | 빌드·배포·설정 | 1,102 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/resources/logback-test.xml` | 설정·기타 | 583 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-chaos-test/src/chaos-test/resources/testcontainers.properties` | 빌드·배포·설정 | 143 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/build.gradle` | 빌드·배포·설정 | 1,643 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt` | 현재 파이프라인 코드 | 1,130 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/config/CleanupProperties.kt` | 현재 파이프라인 코드 | 610 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt` | 현재 파이프라인 코드 | 3,734 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/controller/InboxCleanupResponse.kt` | 현재 파이프라인 코드 | 134 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt` | 현재 코드 | 2,930 | 77 | 수동 심층 검토+교차검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/inbox/InboxProperties.kt` | 현재 파이프라인 코드 | 397 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/service/RunCleanupService.kt` | 현재 파이프라인 코드 | 3,840 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/kotlin/maple/cleanup/service/StaleKafkaSkipService.kt` | 현재 파이프라인 코드 | 6,108 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/resources/application-local.yml` | 현재 파이프라인 코드 | 26 | 2 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/resources/application.yml` | 현재 파이프라인 코드 | 1,269 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/main/resources/logback-spring.xml` | 현재 파이프라인 코드 | 2,068 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/test/kotlin/maple/cleanup/config/CleanupPropertiesTest.kt` | 테스트 | 1,618 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt` | 테스트 | 5,888 | 155 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/test/kotlin/maple/cleanup/inbox/ConsumedChunkInboxTest.kt` | 테스트 | 2,633 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-cleanup/src/test/kotlin/maple/cleanup/service/RunCleanupServiceTest.kt` | 테스트 | 5,633 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/build.gradle` | 빌드·배포·설정 | 2,486 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/avro/ocid-mapping.avsc` | 설정·기타 | 650 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/avro/result.avsc` | 설정·기타 | 1,141 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/avro/snapshot.avsc` | 설정·기타 | 1,362 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/common/cleanup/RunCleanupExecutor.kt` | 기타 애플리케이션 코드 | 4,083 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/common/cleanup/RunInfo.kt` | 기타 애플리케이션 코드 | 182 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/common/cleanup/RunRetentionPolicy.kt` | 기타 애플리케이션 코드 | 885 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/common/parser/StreamingChunkParser.kt` | 기타 애플리케이션 코드 | 2,942 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/cache/DomainCache.kt` | 기타 애플리케이션 코드 | 534 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/cache/LongCounter.kt` | 기타 애플리케이션 코드 | 232 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/CalculatorResultChunkReadyEvent.kt` | 기타 애플리케이션 코드 | 672 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkConsumedEvent.kt` | 기타 애플리케이션 코드 | 503 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionIdentity.kt` | 기타 애플리케이션 코드 | 194 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionType.kt` | 기타 애플리케이션 코드 | 166 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/SnapshotChunkReadyEvent.kt` | 기타 애플리케이션 코드 | 525 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/SnapshotRunCompletedEvent.kt` | 기타 애플리케이션 코드 | 511 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/event/SnapshotRunFailedEvent.kt` | 기타 애플리케이션 코드 | 370 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/executor/TaskContext.kt` | 기타 애플리케이션 코드 | 2,078 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/function/ThrowingSupplier.kt` | 기타 애플리케이션 코드 | 703 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/function/ThrowingSupplierUtils.kt` | 기타 애플리케이션 코드 | 1,249 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/metrics/MetricsRegistry.kt` | 기타 애플리케이션 코드 | 410 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/metrics/Timer.kt` | 기타 애플리케이션 코드 | 225 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/resource/ResourceLoader.kt` | 기타 애플리케이션 코드 | 2,073 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` | 기타 애플리케이션 코드 | 5,703 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/common/util/FingerprintUtil.kt` | 기타 애플리케이션 코드 | 1,034 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/CommonErrorCode.kt` | 기타 애플리케이션 코드 | 4,849 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/ErrorCode.kt` | 기타 애플리케이션 코드 | 200 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/dto/ErrorResponse.kt` | 기타 애플리케이션 코드 | 3,734 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/AdminMemberNotFoundException.kt` | 기타 애플리케이션 코드 | 874 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/AdminNotFoundException.kt` | 기타 애플리케이션 코드 | 440 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ApiTimeoutException.kt` | 기타 애플리케이션 코드 | 769 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ArtifactNotFoundException.kt` | 기타 애플리케이션 코드 | 477 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/AtomicFetchException.kt` | 기타 애플리케이션 코드 | 375 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CacheDataNotFoundException.kt` | 기타 애플리케이션 코드 | 687 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CachePersistenceException.kt` | 기타 애플리케이션 코드 | 704 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CharacterNotFoundException.kt` | 기타 애플리케이션 코드 | 618 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CharacterNotOwnedException.kt` | 기타 애플리케이션 코드 | 751 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CompressionException.kt` | 기타 애플리케이션 코드 | 532 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CriticalTransactionFailureException.kt` | 기타 애플리케이션 코드 | 663 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/CubeDataInitializationException.kt` | 기타 애플리케이션 코드 | 872 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/DatabaseNamedLockException.kt` | 기타 애플리케이션 코드 | 1,146 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/DeveloperNotFoundException.kt` | 기타 애플리케이션 코드 | 626 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/DistributedLockException.kt` | 기타 애플리케이션 코드 | 578 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/DuplicateLikeException.kt` | 기타 애플리케이션 코드 | 617 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/EquipmentDataProcessingException.kt` | 기타 애플리케이션 코드 | 858 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/EventProcessingException.kt` | 기타 애플리케이션 코드 | 1,441 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ExpectationCalculationUnavailableException.kt` | 기타 애플리케이션 코드 | 668 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ExternalApiException.kt` | 기타 애플리케이션 코드 | 960 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ExternalServiceException.kt` | 기타 애플리케이션 코드 | 662 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InsufficientPointException.kt` | 기타 애플리케이션 코드 | 1,025 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InsufficientResourceException.kt` | 기타 애플리케이션 코드 | 636 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InternalSystemException.kt` | 기타 애플리케이션 코드 | 828 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InvalidAdminFingerprintException.kt` | 기타 애플리케이션 코드 | 931 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InvalidApiKeyException.kt` | 기타 애플리케이션 코드 | 840 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InvalidCharacterStateException.kt` | 기타 애플리케이션 코드 | 1,016 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InvalidPotentialGradeException.kt` | 기타 애플리케이션 코드 | 966 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/InvalidRefreshTokenException.kt` | 기타 애플리케이션 코드 | 837 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/LikeSyncCircuitOpenException.kt` | 기타 애플리케이션 코드 | 711 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/MapleDataProcessingException.kt` | 기타 애플리케이션 코드 | 708 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/MonitoringException.kt` | 기타 애플리케이션 코드 | 1,043 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ObservabilityException.kt` | 기타 애플리케이션 코드 | 670 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/OptionParseException.kt` | 기타 애플리케이션 코드 | 647 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/ProbabilityInvariantException.kt` | 기타 애플리케이션 코드 | 626 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/QueuePublishException.kt` | 기타 애플리케이션 코드 | 712 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/RateLimitExceededException.kt` | 기타 애플리케이션 코드 | 1,531 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/RefreshTokenExpiredException.kt` | 기타 애플리케이션 코드 | 837 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/SelfLikeNotAllowedException.kt` | 기타 애플리케이션 코드 | 914 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/SenderMemberNotFoundException.kt` | 기타 애플리케이션 코드 | 630 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/SessionNotFoundException.kt` | 기타 애플리케이션 코드 | 854 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/StarforceNotInitializedException.kt` | 기타 애플리케이션 코드 | 609 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/SystemException.kt` | 기타 애플리케이션 코드 | 926 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/TokenReusedException.kt` | 기타 애플리케이션 코드 | 795 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/TransactionSnapshotException.kt` | 기타 애플리케이션 코드 | 377 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/UnsupportedCalculationEngineException.kt` | 기타 애플리케이션 코드 | 914 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/base/BaseException.kt` | 기타 애플리케이션 코드 | 2,058 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/base/ClientBaseException.kt` | 기타 애플리케이션 코드 | 1,732 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/base/ServerBaseException.kt` | 기타 애플리케이션 코드 | 2,264 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/marker/CircuitBreakerIgnoreMarker.kt` | 기타 애플리케이션 코드 | 435 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/exception/marker/CircuitBreakerRecordMarker.kt` | 기타 애플리케이션 코드 | 454 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/error/package-info.kt` | 기타 애플리케이션 코드 | 299 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/event/EventHandler.kt` | 기타 애플리케이션 코드 | 1,828 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/event/EventPriority.kt` | 기타 애플리케이션 코드 | 723 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/event/EventVersion.kt` | 기타 애플리케이션 코드 | 2,245 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/response/ApiResponse.kt` | 기타 애플리케이션 코드 | 898 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/CompressionUtils.kt` | 기타 애플리케이션 코드 | 237 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/ExceptionUtils.kt` | 기타 애플리케이션 코드 | 4,522 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/GzipUtils.kt` | 기타 애플리케이션 코드 | 2,922 | 100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/HashUtils.kt` | 기타 애플리케이션 코드 | 293 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/InterruptUtils.kt` | 기타 애플리케이션 코드 | 1,994 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt` | 기타 애플리케이션 코드 | 4,044 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/common/cleanup/RunCleanupExecutorTest.kt` | 테스트 | 3,261 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/common/cleanup/RunRetentionPolicyTest.kt` | 테스트 | 3,303 | 110 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/common/parser/StreamingChunkParserTest.kt` | 테스트 | 4,759 | 125 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/expectation/common/util/FingerprintUtilTest.kt` | 테스트 | 1,978 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/expectation/error/CommonErrorCodeTest.kt` | 테스트 | 706 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-common/src/test/kotlin/maple/expectation/error/exception/ArtifactNotFoundExceptionTest.kt` | 테스트 | 938 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/.jqwik-database` | 설정·기타 | 4 | binary | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/build.gradle` | 빌드·배포·설정 | 1,046 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/core/domain/chunk/Chunk.kt` | 기타 애플리케이션 코드 | 159 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkProcessInput.kt` | 기타 애플리케이션 코드 | 178 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkReader.kt` | 기타 애플리케이션 코드 | 113 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkTransformer.kt` | 기타 애플리케이션 코드 | 123 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkWriter.kt` | 기타 애플리케이션 코드 | 114 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/auth/JwtGeneratorPort.kt` | 기타 애플리케이션 코드 | 225 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/auth/JwtParserPort.kt` | 기타 애플리케이션 코드 | 196 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/auth/JwtPayload.kt` | 기타 애플리케이션 코드 | 806 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/auth/event/CharacterFetchRequest.kt` | 기타 애플리케이션 코드 | 381 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/auth/event/CharacterFetchResponse.kt` | 기타 애플리케이션 코드 | 486 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/CubeRateCalculator.kt` | 기타 애플리케이션 코드 | 1,836 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/BaseEquipmentItem.kt` | 기타 애플리케이션 코드 | 1,176 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/BaseItem.kt` | 기타 애플리케이션 코드 | 869 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/EnhanceDecorator.kt` | 기타 애플리케이션 코드 | 781 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/EquipmentEnhanceDecorator.kt` | 기타 애플리케이션 코드 | 1,088 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/EquipmentExpectationCalculatorPort.kt` | 기타 애플리케이션 코드 | 3,172 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/domain/ExpectationCalculatorPort.kt` | 기타 애플리케이션 코드 | 916 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/port/CubeCostPort.kt` | 기타 애플리케이션 코드 | 941 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/port/CubeTrialsPort.kt` | 기타 애플리케이션 코드 | 477 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/port/StarforceLookupPort.kt` | 기타 애플리케이션 코드 | 3,583 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/calculator/port/StatParserPort.kt` | 기타 애플리케이션 코드 | 549 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/ExecutionContext.kt` | 기타 애플리케이션 코드 | 293 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/auth/RefreshToken.kt` | 기타 애플리케이션 코드 | 2,663 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/auth/Session.kt` | 기타 애플리케이션 코드 | 3,549 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/cost/CostFormatter.kt` | 기타 애플리케이션 코드 | 4,162 | 146 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/equipment/SecondaryWeaponCategory.kt` | 기타 애플리케이션 코드 | 2,514 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/event/IntegrationEvent.kt` | 기타 애플리케이션 코드 | 2,579 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/flame/FlameEquipCategory.kt` | 기타 애플리케이션 코드 | 814 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/flame/FlameOptionType.kt` | 기타 애플리케이션 코드 | 1,825 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/flame/FlameStageProbability.kt` | 기타 애플리케이션 코드 | 1,418 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/flame/FlameStatTable.kt` | 기타 애플리케이션 코드 | 12,048 | 312 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/flame/FlameType.kt` | 기타 애플리케이션 코드 | 287 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/AlertMessage.kt` | 기타 애플리케이션 코드 | 1,344 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/AlertPriority.kt` | 기타 애플리케이션 코드 | 438 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/CalculationResult.kt` | 기타 애플리케이션 코드 | 2,089 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/CubeRate.kt` | 기타 애플리케이션 코드 | 1,609 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/CubeType.kt` | 기타 애플리케이션 코드 | 513 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/ItemPrice.kt` | 기타 애플리케이션 코드 | 1,340 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/Page.kt` | 기타 애플리케이션 코드 | 2,606 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialGrade.kt` | 기타 애플리케이션 코드 | 1,293 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialStat.kt` | 기타 애플리케이션 코드 | 1,354 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/calculator/DensePmf.kt` | 기타 애플리케이션 코드 | 3,636 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/calculator/DiceRollProbability.kt` | 기타 애플리케이션 코드 | 882 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/calculator/SparsePmf.kt` | 기타 애플리케이션 코드 | 4,929 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/character/CharacterId.kt` | 기타 애플리케이션 코드 | 498 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/character/CharacterView.kt` | 기타 애플리케이션 코드 | 1,694 | 74 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/character/GameCharacter.kt` | 기타 애플리케이션 코드 | 4,716 | 131 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/character/UserIgn.kt` | 기타 애플리케이션 코드 | 469 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/equipment/CharacterEquipment.kt` | 기타 애플리케이션 코드 | 3,228 | 88 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/equipment/EquipmentData.kt` | 기타 애플리케이션 코드 | 1,062 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/like/CharacterLike.kt` | 기타 애플리케이션 코드 | 2,912 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/like/LikeId.kt` | 기타 애플리케이션 코드 | 265 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/like/LikeToggleResult.kt` | 기타 애플리케이션 코드 | 217 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/like/LikeToggleWithCount.kt` | 기타 애플리케이션 코드 | 306 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/model/security/AuthenticatedUser.kt` | 기타 애플리케이션 코드 | 1,926 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/nexon/NexonApiEventType.kt` | 기타 애플리케이션 코드 | 512 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/service/calculator/ProbabilityConverter.kt` | 기타 애플리케이션 코드 | 6,156 | 187 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/stat/StatParser.kt` | 기타 애플리케이션 코드 | 667 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/domain/stat/StatType.kt` | 기타 애플리케이션 코드 | 11,664 | 321 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/cube/CubeCalculationInput.kt` | 기타 애플리케이션 코드 | 11,992 | 297 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/cube/CubeComputeKey.kt` | 기타 애플리케이션 코드 | 770 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/like/FetchResult.kt` | 기타 애플리케이션 코드 | 989 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/like/LikeEvent.kt` | 기타 애플리케이션 코드 | 1,254 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/AddOption.kt` | 기타 애플리케이션 코드 | 274 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/CalculationInput.kt` | 기타 애플리케이션 코드 | 241 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentCalculationInput.kt` | 기타 애플리케이션 코드 | 3,816 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentExpectationResponseV4.kt` | 기타 애플리케이션 코드 | 14,444 | 321 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItem.kt` | 기타 애플리케이션 코드 | 425 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverter.kt` | 기타 애플리케이션 코드 | 1,416 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentPart.kt` | 기타 애플리케이션 코드 | 394 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentSlot.kt` | 기타 애플리케이션 코드 | 875 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/PotentialLines.kt` | 기타 애플리케이션 코드 | 359 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/StarforceScrollFlag.kt` | 기타 애플리케이션 코드 | 408 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/event/ExpectationCalculationCompletedEvent.kt` | 기타 애플리케이션 코드 | 3,321 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/flame/component/FlameScoreResolver.kt` | 기타 애플리케이션 코드 | 1,707 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/flame/config/BossEquipmentRegistry.kt` | 기타 애플리케이션 코드 | 3,238 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/flame/config/JobStatMapping.kt` | 기타 애플리케이션 코드 | 2,588 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/flame/port/FlameTrialsPort.kt` | 기타 애플리케이션 코드 | 1,342 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/flame/service/FlameTrialsService.kt` | 기타 애플리케이션 코드 | 1,622 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/chunk/BasicRecord.kt` | 기타 애플리케이션 코드 | 429 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/chunk/CalculatedEquipmentItem.kt` | 기타 애플리케이션 코드 | 665 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/chunk/GroupedEquipmentResult.kt` | 기타 애플리케이션 코드 | 230 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/chunk/OcidMapping.kt` | 기타 애플리케이션 코드 | 117 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJob.kt` | 기타 애플리케이션 코드 | 727 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobClaim.kt` | 기타 애플리케이션 코드 | 131 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobRequestKey.kt` | 기타 애플리케이션 코드 | 405 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobStatus.kt` | 기타 애플리케이션 코드 | 210 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/model/snapshot/CalculationSnapshot.kt` | 기타 애플리케이션 코드 | 482 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/policy/CostCalculationStrategy.kt` | 기타 애플리케이션 코드 | 1,137 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/policy/TableBasedCostStrategy.kt` | 기타 애플리케이션 코드 | 4,041 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AdminPort.kt` | 기타 애플리케이션 코드 | 1,018 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AdmissionPort.kt` | 기타 애플리케이션 코드 | 875 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AlertPort.kt` | 기타 애플리케이션 코드 | 517 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthCommand.kt` | 기타 애플리케이션 코드 | 745 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthPort.kt` | 기타 애플리케이션 코드 | 1,025 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthResult.kt` | 기타 애플리케이션 코드 | 1,276 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/BatchComputeBuffer.kt` | 기타 애플리케이션 코드 | 1,083 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/BulkLoadPort.kt` | 기타 애플리케이션 코드 | 1,576 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/CacheManagerPort.kt` | 기타 애플리케이션 코드 | 1,379 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/CalculationQueuePort.kt` | 기타 애플리케이션 코드 | 1,048 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/CharacterViewQueryPort.kt` | 기타 애플리케이션 코드 | 1,885 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/DonationCommand.kt` | 기타 애플리케이션 코드 | 675 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/DonationPort.kt` | 기타 애플리케이션 코드 | 822 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/ExecutorPort.kt` | 기타 애플리케이션 코드 | 2,795 | 100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/ExpectationV4Port.kt` | 기타 애플리케이션 코드 | 3,637 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/LikeTogglePort.kt` | 기타 애플리케이션 코드 | 1,669 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/TaskReceipt.kt` | 기타 애플리케이션 코드 | 445 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/TaskStatus.kt` | 기타 애플리케이션 코드 | 375 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/TaskStatusPort.kt` | 기타 애플리케이션 코드 | 464 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/inbound/TokenResult.kt` | 기타 애플리케이션 코드 | 1,015 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AiAnalysisPort.kt` | 기타 애플리케이션 코드 | 3,160 | 118 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AlertNotificationPort.kt` | 기타 애플리케이션 코드 | 1,428 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AlertPort.kt` | 기타 애플리케이션 코드 | 968 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AlertPublisher.kt` | 기타 애플리케이션 코드 | 1,535 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AnomalyDetectionPort.kt` | 기타 애플리케이션 코드 | 3,011 | 118 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/AtomicFetchStrategy.kt` | 기타 애플리케이션 코드 | 940 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/BackoffStrategy.kt` | 기타 애플리케이션 코드 | 1,430 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/BufferStatusQuery.kt` | 기타 애플리케이션 코드 | 926 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CacheWarmupPort.kt` | 기타 애플리케이션 코드 | 469 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationInputPort.kt` | 기타 애플리케이션 코드 | 316 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt` | 기타 애플리케이션 코드 | 1,629 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt` | 기타 애플리케이션 코드 | 1,352 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CharacterOcidPort.kt` | 기타 애플리케이션 코드 | 2,505 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/ChunkFileReaderPort.kt` | 기타 애플리케이션 코드 | 776 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CubeRatePort.kt` | 기타 애플리케이션 코드 | 979 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/EquipmentDataPort.kt` | 기타 애플리케이션 코드 | 1,468 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/EventPublisher.kt` | 기타 애플리케이션 코드 | 2,038 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/ExpectationBufferPort.kt` | 기타 애플리케이션 코드 | 530 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/ExpectationCalcMessage.kt` | 기타 애플리케이션 코드 | 515 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/FanOutQueuePort.kt` | 기타 애플리케이션 코드 | 577 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/GameCharacterPort.kt` | 기타 애플리케이션 코드 | 1,912 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/ItemPricePort.kt` | 기타 애플리케이션 코드 | 908 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt` | 기타 애플리케이션 코드 | 1,847 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/MessageQueue.kt` | 기타 애플리케이션 코드 | 863 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/MessageQueuePort.kt` | 기타 애플리케이션 코드 | 1,504 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/MessageTopic.kt` | 기타 애플리케이션 코드 | 1,068 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/MetricsQueryPort.kt` | 기타 애플리케이션 코드 | 1,522 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/NexonApiOutboxMetricsPort.kt` | 기타 애플리케이션 코드 | 628 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/NexonApiOutboxProcessorPort.kt` | 기타 애플리케이션 코드 | 881 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/NexonDataCollectorPort.kt` | 기타 애플리케이션 코드 | 1,700 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/OcidQueryPort.kt` | 기타 애플리케이션 코드 | 1,116 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt` | 기타 애플리케이션 코드 | 614 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PersistenceTrackerPort.kt` | 기타 애플리케이션 코드 | 358 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PersistenceTrackerStrategy.kt` | 기타 애플리케이션 코드 | 1,725 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PolicyPort.kt` | 기타 애플리케이션 코드 | 843 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PopularCharacterTrackerPort.kt` | 기타 애플리케이션 코드 | 677 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PotentialStatPort.kt` | 기타 애플리케이션 코드 | 900 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/PureCalculationPort.kt` | 기타 애플리케이션 코드 | 273 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueWriterPort.kt` | 기타 애플리케이션 코드 | 1,734 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/ShutdownDataPersistencePort.kt` | 기타 애플리케이션 코드 | 501 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/SnapshotObjectStore.kt` | 기타 애플리케이션 코드 | 424 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/SystemMetricsPort.kt` | 기타 애플리케이션 코드 | 455 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/TokenPort.kt` | 기타 애플리케이션 코드 | 671 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/ConsumeResult.kt` | 기타 애플리케이션 코드 | 239 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/DomainEventAppender.kt` | 기타 애플리케이션 코드 | 205 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/MQTopicGroup.kt` | 기타 애플리케이션 코드 | 298 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/mq/MessageHandle.kt` | 기타 애플리케이션 코드 | 107 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/probability/FlameDpCalculator.kt` | 기타 애플리케이션 코드 | 4,917 | 170 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/probability/FlameScoreCalculator.kt` | 기타 애플리케이션 코드 | 8,482 | 238 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/probability/ProbabilityConvolver.kt` | 기타 애플리케이션 코드 | 4,448 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/probability/TailProbabilityCalculator.kt` | 기타 애플리케이션 코드 | 2,618 | 88 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/starforce/domain/NoljangProbabilityCalculator.kt` | 기타 애플리케이션 코드 | 7,872 | 231 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/starforce/domain/StarforceCalculationEngine.kt` | 기타 애플리케이션 코드 | 9,131 | 294 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/starforce/domain/StarforceConstants.kt` | 기타 애플리케이션 코드 | 1,928 | 60 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/main/kotlin/maple/expectation/core/util/KahanSummation.kt` | 기타 애플리케이션 코드 | 2,030 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/java/maple/expectation/arch/CoreDependencyRuleTest.java` | 테스트 | 23,418 | 606 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/java/maple/expectation/domain/cost/CostFormatterTest.java` | 테스트 | 3,271 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/java/maple/expectation/properties/BoundaryConditionsProperties.java` | 테스트 | 8,224 | 255 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/java/maple/expectation/properties/ExpectationValueProperties.java` | 테스트 | 16,244 | 475 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/java/maple/expectation/properties/ProbabilityContractsProperties.java` | 테스트 | 13,136 | 417 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkReaderTest.kt` | 테스트 | 671 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTest.kt` | 테스트 | 835 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkTransformerTest.kt` | 테스트 | 1,276 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/core/domain/chunk/ChunkWriterTest.kt` | 테스트 | 869 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/core/dto/cube/CubeComputeKeyTest.kt` | 테스트 | 2,041 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/core/dto/v4/CalculationInputTest.kt` | 테스트 | 2,619 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverterTest.kt` | 테스트 | 3,071 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/core/dto/v4/PotentialLinesTest.kt` | 테스트 | 1,143 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/core/util/BigDecimalVsDoublePrecisionTest.kt` | 테스트 | 8,576 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/properties/GoldenMasterTests.kt` | 테스트 | 14,318 | 426 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/test/CoreUnitTestTemplate.kt` | 테스트 | 4,126 | 146 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/test/CoreUnitTestTemplateExample.kt` | 테스트 | 7,695 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/kotlin/maple/expectation/test/README.md` | 테스트 | 8,785 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-core/src/test/resources/junit-platform.properties` | 테스트 | 565 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/.gitignore` | 설정·기타 | 19 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/build.gradle` | 빌드·배포·설정 | 3,129 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt` | 현재 파이프라인 코드 | 2,312 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt` | 현재 파이프라인 코드 | 1,535 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` | 현재 파이프라인 코드 | 4,967 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthExecutorConfig.kt` | 현재 파이프라인 코드 | 1,671 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` | 현재 파이프라인 코드 | 4,215 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/config/NexonHttpClientProperties.kt` | 현재 파이프라인 코드 | 544 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/config/StreamingChunkParserConfig.kt` | 현재 파이프라인 코드 | 665 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiFetchCommand.kt` | 현재 파이프라인 코드 | 772 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiPayloadRef.kt` | 현재 파이프라인 코드 | 236 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiProvider.kt` | 현재 파이프라인 코드 | 138 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/event/UrgentEventPublisher.kt` | 현재 파이프라인 코드 | 2,080 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt` | 현재 파이프라인 코드 | 5,995 | 131 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt` | 현재 파이프라인 코드 | 2,250 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt` | 현재 파이프라인 코드 | 9,815 | 222 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt` | 현재 파이프라인 코드 | 1,139 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/CleanupMetrics.kt` | 현재 파이프라인 코드 | 1,816 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/ExternalApiMetrics.kt` | 현재 파이프라인 코드 | 2,904 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/SchedulerMetrics.kt` | 현재 파이프라인 코드 | 1,862 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/SnapshotFetchMetrics.kt` | 현재 파이프라인 코드 | 2,207 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/SnapshotVolumeMetrics.kt` | 현재 파이프라인 코드 | 2,368 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/OcidResponseParser.kt` | 현재 파이프라인 코드 | 1,280 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/RankingEntryParser.kt` | 현재 파이프라인 코드 | 1,812 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/UrgentOcidResponseParser.kt` | 현재 파이프라인 코드 | 1,232 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetBenchmark.kt` | 현재 파이프라인 코드 | 3,126 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReader.kt` | 현재 파이프라인 코드 | 1,222 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriter.kt` | 현재 파이프라인 코드 | 2,167 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiClientPort.kt` | 현재 파이프라인 코드 | 381 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/reader/CharacterNameReader.kt` | 현재 파이프라인 코드 | 1,857 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` | 현재 파이프라인 코드 | 10,394 | 241 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt` | 현재 파이프라인 코드 | 1,088 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt` | 현재 파이프라인 코드 | 427 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt` | 현재 파이프라인 코드 | 243 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt` | 현재 파이프라인 코드 | 680 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt` | 현재 파이프라인 코드 | 977 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` | 현재 파이프라인 코드 | 7,206 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | 현재 파이프라인 코드 | 18,818 | 411 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStopSignal.kt` | 현재 파이프라인 코드 | 935 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStoppedException.kt` | 현재 파이프라인 코드 | 475 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt` | 현재 파이프라인 코드 | 9,110 | 215 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchProgress.kt` | 현재 파이프라인 코드 | 977 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` | 현재 파이프라인 코드 | 4,532 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/FetchProgressTracker.kt` | 현재 파이프라인 코드 | 1,700 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractor.kt` | 현재 파이프라인 코드 | 508 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` | 현재 파이프라인 코드 | 4,197 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` | 현재 파이프라인 코드 | 18,243 | 404 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` | 현재 파이프라인 코드 | 8,760 | 196 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunIdGenerator.kt` | 현재 파이프라인 코드 | 434 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriter.kt` | 현재 파이프라인 코드 | 745 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerClockConfig.kt` | 현재 파이프라인 코드 | 291 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt` | 현재 파이프라인 코드 | 2,116 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLogger.kt` | 현재 파이프라인 코드 | 1,518 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt` | 현재 파이프라인 코드 | 985 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` | 현재 파이프라인 코드 | 9,791 | 253 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` | 현재 코드 | 15,261 | 346 | 수동 심층 검토+교차검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt` | 현재 파이프라인 코드 | 2,651 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt` | 현재 파이프라인 코드 | 7,956 | 185 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt` | 현재 파이프라인 코드 | 4,461 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SinkEventPublisher.kt` | 현재 파이프라인 코드 | 1,661 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkManifest.kt` | 현재 파이프라인 코드 | 1,000 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkRecord.kt` | 현재 파이프라인 코드 | 2,245 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkingProperties.kt` | 현재 파이프라인 코드 | 1,049 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriter.kt` | 현재 파이프라인 코드 | 1,155 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt` | 현재 파이프라인 코드 | 4,796 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/KafkaSnapshotChunkEventPublisher.kt` | 현재 파이프라인 코드 | 2,988 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/NoOpSnapshotChunkEventPublisher.kt` | 현재 파이프라인 코드 | 1,258 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotChunkEventPublisher.kt` | 현재 파이프라인 코드 | 569 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventProperties.kt` | 현재 파이프라인 코드 | 674 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt` | 현재 파이프라인 코드 | 5,083 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt` | 현재 파이프라인 코드 | 7,116 | 183 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/resources/application-local.yml` | 현재 파이프라인 코드 | 402 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/resources/application-prod.yml` | 현재 파이프라인 코드 | 177 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/main/resources/application.yml` | 현재 설정 | 4,716 | 140 | 수동 심층 검토+교차검증 |
| `module-external-api/src/main/resources/logback-spring.xml` | 현재 파이프라인 코드 | 2,657 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriterTest.kt` | 테스트 | 2,867 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/cache/OcidCacheProviderTest.kt` | 테스트 | 6,073 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/dataflow/DataflowContractTest.kt` | 테스트 | 14,066 | 278 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/loop/LoopExecutorConfigTest.kt` | 테스트 | 1,332 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt` | 테스트 | 13,122 | 291 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/metrics/SchedulerMetricsTest.kt` | 테스트 | 1,666 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetBenchmarkTest.kt` | 테스트 | 1,264 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReaderTest.kt` | 테스트 | 1,117 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriterTest.kt` | 테스트 | 1,021 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` | 테스트 | 28,938 | 636 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/LoopStateTest.kt` | 테스트 | 1,516 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt` | 테스트 | 1,713 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` | 테스트 | 10,975 | 239 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerStopTest.kt` | 테스트 | 17,671 | 349 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` | 테스트 | 41,256 | 832 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/PhaseStopSignalTest.kt` | 테스트 | 1,843 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupportStopTest.kt` | 테스트 | 2,906 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchProgressTest.kt` | 테스트 | 1,972 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhaseTest.kt` | 테스트 | 3,462 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/FetchProgressTrackerTest.kt` | 테스트 | 2,022 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/HttpStatusExtractorTest.kt` | 테스트 | 1,356 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhaseTest.kt` | 테스트 | 3,238 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` | 테스트 | 14,502 | 313 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseStopTest.kt` | 테스트 | 1,339 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt` | 테스트 | 4,675 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunIdGeneratorTest.kt` | 테스트 | 672 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RunMarkerWriterTest.kt` | 테스트 | 1,301 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerProgressLoggerTest.kt` | 테스트 | 1,053 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiterTest.kt` | 테스트 | 1,098 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerAsyncTest.kt` | 테스트 | 4,159 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkFileManagerTest.kt` | 테스트 | 4,613 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSinkTest.kt` | 테스트 | 5,220 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/EndpointSinkFactoryTest.kt` | 테스트 | 3,474 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt` | 테스트 | 13,187 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt` | 테스트 | 7,744 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotChunkManifestWriterTest.kt` | 테스트 | 2,390 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriterTest.kt` | 테스트 | 1,856 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt` | 테스트 | 6,359 | 148 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/test/ExtApiBlockingPrimitiveGateTest.kt` | 테스트 | 3,500 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-external-api/src/test/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumerTest.kt` | 테스트 | 8,410 | 199 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/build.gradle` | 빌드·배포·설정 | 6,102 | 210 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/EquipmentEnhanceDecorator.java` | 기타 애플리케이션 코드 | 1,229 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/EquipmentExpectationCalculator.java` | 기타 애플리케이션 코드 | 4,315 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/EquipmentExpectationCalculatorFactory.java` | 기타 애플리케이션 코드 | 4,693 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/impl/BaseEquipmentItem.java` | 기타 애플리케이션 코드 | 1,324 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/impl/StarforceDecoratorV4.java` | 기타 애플리케이션 코드 | 3,717 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/AbstractCubeDecorator.java` | 기타 애플리케이션 코드 | 6,542 | 243 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/AbstractCubeDecoratorV4.java` | 기타 애플리케이션 코드 | 7,374 | 247 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/CubeServiceImpl.java` | 기타 애플리케이션 코드 | 7,949 | 212 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/CubeTrialsProvider.java` | 기타 애플리케이션 코드 | 393 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/CubeComputeBuffer.java` | 기타 애플리케이션 코드 | 1,692 | 58 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/CubeDpCalculator.java` | 기타 애플리케이션 코드 | 3,754 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/CubeSlotCountResolver.java` | 기타 애플리케이션 코드 | 775 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/DpModeInferrer.java` | 기타 애플리케이션 코드 | 7,997 | 231 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/SlotDistributionBuilder.java` | 기타 애플리케이션 코드 | 7,211 | 205 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/component/StatValueExtractor.java` | 기타 애플리케이션 코드 | 3,908 | 120 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/cube/policy/CubeCostPolicy.java` | 기타 애플리케이션 코드 | 2,202 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java` | 기타 애플리케이션 코드 | 20,521 | 499 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/flame/FlameInputResolver.java` | 기타 애플리케이션 코드 | 2,699 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/starforce/NoljangProbabilityTable.java` | 기타 애플리케이션 코드 | 7,525 | 254 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/application/service/starforce/StarforceLookupAdapter.java` | 기타 애플리케이션 코드 | 14,167 | 489 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/config/CubeEngineFeatureFlag.java` | 기타 애플리케이션 코드 | 1,533 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/config/TableMassConfig.java` | 기타 애플리케이션 코드 | 952 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/infrastructure/external/impl/NexonDataCollector.java` | 기타 애플리케이션 코드 | 9,306 | 257 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/infrastructure/like/DatabaseLikeProcessor.java` | 기타 애플리케이션 코드 | 770 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/infrastructure/like/LikeProcessor.java` | 기타 애플리케이션 코드 | 388 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/infrastructure/like/LikeToggleService.java` | 기타 애플리케이션 코드 | 4,891 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/java/maple/expectation/infrastructure/like/OcidResolutionService.java` | 기타 애플리케이션 코드 | 2,180 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationInputPortAdapter.kt` | 기타 애플리케이션 코드 | 1,820 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt` | 기타 애플리케이션 코드 | 6,493 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt` | 기타 애플리케이션 코드 | 5,090 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/MessageQueuePortAdapter.kt` | 기타 애플리케이션 코드 | 2,446 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt` | 기타 애플리케이션 코드 | 2,377 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/AdditionalCubeDecoratorV4.kt` | 기타 애플리케이션 코드 | 1,836 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/BlackCubeDecoratorV4.kt` | 기타 애플리케이션 코드 | 1,755 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/application/service/calculator/v4/impl/RedCubeDecoratorV4.kt` | 기타 애플리케이션 코드 | 1,755 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt` | 기타 애플리케이션 코드 | 1,158 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/domain/nexon/NexonApiCharacterData.kt` | 기타 애플리케이션 코드 | 1,590 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/BufferStatusQueryAdapter.kt` | 기타 애플리케이션 코드 | 733 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/CharacterOcidAdapter.kt` | 기타 애플리케이션 코드 | 4,477 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/NexonDataQueueAdapter.kt` | 기타 애플리케이션 코드 | 1,056 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/OcidQueryAdapter.kt` | 기타 애플리케이션 코드 | 1,340 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/PopularCharacterTrackerAdapter.kt` | 기타 애플리케이션 코드 | 960 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/QueueWriterAdapter.kt` | 기타 애플리케이션 코드 | 975 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/adapter/policy/PolicyAdapter.kt` | 기타 애플리케이션 코드 | 666 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/AdaptiveAdmissionControl.kt` | 기타 애플리케이션 코드 | 13,207 | 348 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/AdmissionExceptions.kt` | 기타 애플리케이션 코드 | 720 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/DegradeStrategy.kt` | 기타 애플리케이션 코드 | 5,165 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/GlobalAdmissionControl.kt` | 기타 애플리케이션 코드 | 12,721 | 321 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/PriorityAdmissionControl.kt` | 기타 애플리케이션 코드 | 11,322 | 287 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/SimpleAdmissionControl.kt` | 기타 애플리케이션 코드 | 7,025 | 200 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/AlertPriority.kt` | 기타 애플리케이션 코드 | 255 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/StatelessAlertService.kt` | 기타 애플리케이션 코드 | 3,532 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/AlertChannel.kt` | 기타 애플리케이션 코드 | 739 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/DiscordAlertChannel.kt` | 기타 애플리케이션 코드 | 3,770 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/FallbackSupport.kt` | 기타 애플리케이션 코드 | 489 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/InMemoryAlertBuffer.kt` | 기타 애플리케이션 코드 | 2,460 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/LocalFileAlertChannel.kt` | 기타 애플리케이션 코드 | 2,997 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/factory/MessageFactory.kt` | 기타 애플리케이션 코드 | 3,921 | 114 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/message/AlertMessage.kt` | 기타 애플리케이션 코드 | 705 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/strategy/AlertChannelStrategy.kt` | 기타 애플리케이션 코드 | 612 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/strategy/StatelessAlertChannelStrategy.kt` | 기타 애플리케이션 코드 | 2,404 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/BufferedLike.kt` | 기타 애플리케이션 코드 | 428 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/Locked.kt` | 기타 애플리케이션 코드 | 865 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/LogExecutionTime.kt` | 기타 애플리케이션 코드 | 1,244 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/NexonDataCache.kt` | 기타 애플리케이션 코드 | 371 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/ObservedTransaction.kt` | 기타 애플리케이션 코드 | 402 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/SimpleLogTime.kt` | 기타 애플리케이션 코드 | 310 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/TimedStage.kt` | 기타 애플리케이션 코드 | 1,083 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/TimedTask.kt` | 기타 애플리케이션 코드 | 799 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/annotation/TraceLog.kt` | 기타 애플리케이션 코드 | 374 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/BufferedLikeAspect.kt` | 기타 애플리케이션 코드 | 1,057 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/LockAspect.kt` | 기타 애플리케이션 코드 | 3,406 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/LoggingAspect.kt` | 기타 애플리케이션 코드 | 4,124 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/NexonDataCacheAspect.kt` | 기타 애플리케이션 코드 | 7,829 | 208 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/ObservabilityAspect.kt` | 기타 애플리케이션 코드 | 3,437 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/SimpleLogAspect.kt` | 기타 애플리케이션 코드 | 1,042 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/TraceAspect.kt` | 기타 애플리케이션 코드 | 8,483 | 233 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/WorkerTimingAspect.kt` | 기타 애플리케이션 코드 | 6,844 | 197 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/collector/PerformanceStatisticsCollector.kt` | 기타 애플리케이션 코드 | 1,865 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/context/SkipEquipmentL2CacheContext.kt` | 기타 애플리케이션 코드 | 4,492 | 140 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/context/WorkerMdcKeys.kt` | 기타 애플리케이션 코드 | 1,706 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/util/CustomSpelParser.kt` | 기타 애플리케이션 코드 | 4,078 | 101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/auth/TokenPortImpl.kt` | 기타 애플리케이션 코드 | 1,676 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserService.kt` | 기타 애플리케이션 코드 | 19,550 | 500 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/BatchRequest.kt` | 기타 애플리케이션 코드 | 1,184 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/BatchResult.kt` | 기타 애플리케이션 코드 | 691 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/BatchScheduler.kt` | 기타 애플리케이션 코드 | 3,148 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/DedupeMicroBatchWriter.kt` | 기타 애플리케이션 코드 | 15,747 | 412 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/GameCharacterMicroBatchAdapter.kt` | 기타 애플리케이션 코드 | 2,017 | 58 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/L2CacheMicroBatchAdapter.kt` | 기타 애플리케이션 코드 | 2,689 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/MonitoringReportJob.kt` | 기타 애플리케이션 코드 | 7,837 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/listener/BatchJobRecoveryListener.kt` | 기타 애플리케이션 코드 | 6,148 | 175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/listener/BatchMetricsLogger.kt` | 기타 애플리케이션 코드 | 4,349 | 120 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/reader/OcidReader.kt` | 기타 애플리케이션 코드 | 3,584 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/scheduler/BatchJobRecoveryScheduler.kt` | 기타 애플리케이션 코드 | 9,670 | 262 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/writer/LowPriorityQueueWriter.kt` | 기타 애플리케이션 코드 | 3,649 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/buffer/ExpectationWriteBackBuffer.kt` | 기타 애플리케이션 코드 | 4,959 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/buffer/ExpectationWriteTask.kt` | 기타 애플리케이션 코드 | 1,346 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/AdaptiveThrottler.kt` | 기타 애플리케이션 코드 | 5,216 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/BulkLoaderService.kt` | 기타 애플리케이션 코드 | 18,159 | 513 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/CheckpointManager.kt` | 기타 애플리케이션 코드 | 3,417 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/FailedCharactersTracker.kt` | 기타 애플리케이션 코드 | 7,950 | 251 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/ProgressLogger.kt` | 기타 애플리케이션 코드 | 5,583 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/CacheManagerPortAdapter.kt` | 기타 애플리케이션 코드 | 696 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/CacheType.kt` | 기타 애플리케이션 코드 | 1,091 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/CaffeineOnlyCacheManager.kt` | 기타 애플리케이션 코드 | 2,735 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/RestrictedCacheManager.kt` | 기타 애플리케이션 코드 | 1,093 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt` | 기타 애플리케이션 코드 | 16,835 | 429 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCacheManager.kt` | 기타 애플리케이션 코드 | 7,134 | 193 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/equipment/EquipmentDataResolver.kt` | 기타 애플리케이션 코드 | 4,379 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/equipment/EquipmentFingerprintGenerator.kt` | 기타 애플리케이션 코드 | 3,375 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/expectation/TotalExpectationCacheService.kt` | 기타 애플리케이션 코드 | 10,019 | 236 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationEvent.kt` | 기타 애플리케이션 코드 | 2,943 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationPublisher.kt` | 기타 애플리케이션 코드 | 675 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationSubscriber.kt` | 기타 애플리케이션 코드 | 945 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/InvalidationType.kt` | 기타 애플리케이션 코드 | 625 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/TransactionalCacheInvalidationListener.kt` | 기타 애플리케이션 코드 | 4,327 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifyPublisher.kt` | 기타 애플리케이션 코드 | 3,414 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/PostgresNotifySubscriber.kt` | 기타 애플리케이션 코드 | 12,116 | 325 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorage.kt` | 기타 애플리케이션 코드 | 3,499 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/per/CachedWrapper.kt` | 기타 애플리케이션 코드 | 2,966 | 109 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/per/ProbabilisticCache.kt` | 기타 애플리케이션 코드 | 1,886 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/port/EquipmentCache.kt` | 기타 애플리케이션 코드 | 1,549 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/AbstractTieredCacheService.kt` | 기타 애플리케이션 코드 | 7,532 | 212 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/BatchL2LookupBuffer.kt` | 기타 애플리케이션 코드 | 5,890 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/BatchL2WriteBuffer.kt` | 기타 애플리케이션 코드 | 4,829 | 136 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/CacheStampedeTimeoutException.kt` | 기타 애플리케이션 코드 | 775 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/EquipmentCacheService.kt` | 기타 애플리케이션 코드 | 5,363 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/L2CacheStrategy.kt` | 기타 애플리케이션 코드 | 3,002 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheFactory.kt` | 기타 애플리케이션 코드 | 7,318 | 216 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheStrategy.kt` | 기타 애플리케이션 코드 | 13,758 | 360 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/TypedValue.kt` | 기타 애플리케이션 코드 | 969 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/character/notify/CharacterCreationListener.kt` | 기타 애플리케이션 코드 | 7,212 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/character/notify/CharacterCreationNotifier.kt` | 기타 애플리케이션 코드 | 1,687 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuard.kt` | 기타 애플리케이션 코드 | 1,450 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiter.kt` | 기타 애플리케이션 코드 | 755 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureRejectedException.kt` | 기타 애플리케이션 코드 | 208 | 3 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphore.kt` | 기타 애플리케이션 코드 | 530 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt` | 기타 애플리케이션 코드 | 2,236 | 58 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorQualifier.kt` | 기타 애플리케이션 코드 | 152 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorRegistry.kt` | 기타 애플리케이션 코드 | 334 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelector.kt` | 기타 애플리케이션 코드 | 725 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponent.kt` | 기타 애플리케이션 코드 | 348 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/PostgresSingleFlightStrategy.kt` | 기타 애플리케이션 코드 | 10,662 | 277 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ShutdownPhase.kt` | 기타 애플리케이션 코드 | 124 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/SingleFlightExecutor.kt` | 기타 애플리케이션 코드 | 6,775 | 182 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/SingleFlightStrategy.kt` | 기타 애플리케이션 코드 | 1,872 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncher.kt` | 기타 애플리케이션 코드 | 651 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AdaptiveMicroBatchConfig.kt` | 기타 애플리케이션 코드 | 657 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AdaptiveMicroBatchProperties.kt` | 기타 애플리케이션 코드 | 3,209 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AlertChannelConfig.kt` | 기타 애플리케이션 코드 | 2,368 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AlertConfigurationValidator.kt` | 기타 애플리케이션 코드 | 2,708 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AlertFeatureProperties.kt` | 기타 애플리케이션 코드 | 1,908 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/AlertWebClientConfig.kt` | 기타 애플리케이션 코드 | 2,012 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BatchConfig.kt` | 기타 애플리케이션 코드 | 3,782 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BatchProperties.kt` | 기타 애플리케이션 코드 | 1,779 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BufferConfig.kt` | 기타 애플리케이션 코드 | 1,220 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BufferProperties.kt` | 기타 애플리케이션 코드 | 1,698 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BulkLoadConfig.kt` | 기타 애플리케이션 코드 | 409 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BulkLoadProperties.kt` | 기타 애플리케이션 코드 | 2,631 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CacheProperties.kt` | 기타 애플리케이션 코드 | 3,182 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CaffeineOnlyCacheConfig.kt` | 기타 애플리케이션 코드 | 4,474 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculationPortConfig.kt` | 기타 애플리케이션 코드 | 1,750 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculationProperties.kt` | 기타 애플리케이션 코드 | 898 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculatorEngineAutoConfiguration.kt` | 기타 애플리케이션 코드 | 3,037 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CoreExecutorConfig.kt` | 기타 애플리케이션 코드 | 5,088 | 119 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/DiscordTimeoutProperties.kt` | 기타 애플리케이션 코드 | 1,235 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/EquipmentProcessingExecutorConfig.kt` | 기타 애플리케이션 코드 | 6,063 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/EventConsumerConfig.kt` | 기타 애플리케이션 코드 | 7,511 | 202 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt` | 기타 애플리케이션 코드 | 761 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorLoggingProperties.kt` | 기타 애플리케이션 코드 | 818 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorMetricsConfigurator.kt` | 기타 애플리케이션 코드 | 3,078 | 81 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorProperties.kt` | 기타 애플리케이션 코드 | 5,325 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/GlobalAdmissionConfig.kt` | 기타 애플리케이션 코드 | 442 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/GlobalAdmissionProperties.kt` | 기타 애플리케이션 코드 | 1,548 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/InfraExecutorConfig.kt` | 기타 애플리케이션 코드 | 9,904 | 205 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ItemCalculationExecutorConfig.kt` | 기타 애플리케이션 코드 | 3,799 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/JacksonConfig.kt` | 기타 애플리케이션 코드 | 2,629 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/KafkaConsumerConfig.kt` | 기타 애플리케이션 코드 | 1,854 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/LockHikariConfig.kt` | 기타 애플리케이션 코드 | 4,502 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/MaplestoryApiConfig.kt` | 기타 애플리케이션 코드 | 2,129 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/MicroBatchWriterProperties.kt` | 기타 애플리케이션 코드 | 1,565 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/MonitoringThresholdProperties.kt` | 기타 애플리케이션 코드 | 2,347 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/NexonApiProperties.kt` | 기타 애플리케이션 코드 | 5,211 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/OpenTelemetryConfig.kt` | 기타 애플리케이션 코드 | 1,387 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/PerCacheExecutorConfig.kt` | 기타 애플리케이션 코드 | 3,703 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/PostgresL2CacheConfig.kt` | 기타 애플리케이션 코드 | 6,923 | 184 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/PostgresLockHikariConfig.kt` | 기타 애플리케이션 코드 | 5,339 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/PostgresNotifyConfig.kt` | 기타 애플리케이션 코드 | 5,681 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/PresetCalculationExecutorConfig.kt` | 기타 애플리케이션 코드 | 6,266 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RejectionPolicyFactory.kt` | 기타 애플리케이션 코드 | 10,829 | 253 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ResilienceConfig.kt` | 기타 애플리케이션 코드 | 756 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RestControllerExecutorConfig.kt` | 기타 애플리케이션 코드 | 2,770 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/SchedulerConfig.kt` | 기타 애플리케이션 코드 | 8,906 | 213 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/SchedulerProperties.kt` | 기타 애플리케이션 코드 | 790 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/SingleFlightExecutorFactory.kt` | 기타 애플리케이션 코드 | 1,104 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TaskDecoratorFactory.kt` | 기타 애플리케이션 코드 | 2,544 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TimeoutProperties.kt` | 기타 애플리케이션 코드 | 944 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TransactionConfig.kt` | 기타 애플리케이션 코드 | 3,658 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/VtExecutorConfig.kt` | 기타 애플리케이션 코드 | 3,173 | 79 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/package-info.kt` | 기타 애플리케이션 코드 | 345 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverter.kt` | 기타 애플리케이션 코드 | 2,582 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/EventDispatcher.kt` | 기타 애플리케이션 코드 | 6,601 | 180 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/HighPriorityEventConsumer.kt` | 기타 애플리케이션 코드 | 539 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/IntegrationEventConsumer.kt` | 기타 애플리케이션 코드 | 3,142 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/LowPriorityEventConsumer.kt` | 기타 애플리케이션 코드 | 536 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/BasicExecutor.kt` | 기타 애플리케이션 코드 | 1,152 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/CheckedLogicExecutor.kt` | 기타 애플리케이션 코드 | 7,378 | 185 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/DefaultCheckedLogicExecutor.kt` | 기타 애플리케이션 코드 | 9,055 | 244 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/DefaultLogicExecutor.kt` | 기타 애플리케이션 코드 | 11,334 | 301 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/LogicExecutor.kt` | 기타 애플리케이션 코드 | 4,029 | 124 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/ResilientExecutor.kt` | 기타 애플리케이션 코드 | 1,126 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/SafeExecutor.kt` | 기타 애플리케이션 코드 | 509 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/StepTimer.kt` | 기타 애플리케이션 코드 | 1,531 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/TaskContext.kt` | 기타 애플리케이션 코드 | 2,642 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/CircuitBreakerClassification.kt` | 기타 애플리케이션 코드 | 1,811 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/DefaultExceptionClassifier.kt` | 기타 애플리케이션 코드 | 2,258 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/ExceptionClassifier.kt` | 기타 애플리케이션 코드 | 3,745 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/function/CheckedRunnable.kt` | 기타 애플리케이션 코드 | 1,115 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/function/CheckedSupplier.kt` | 기타 애플리케이션 코드 | 1,148 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/function/ThrowingRunnable.kt` | 기타 애플리케이션 코드 | 1,245 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/ExecutionOutcome.kt` | 기타 애플리케이션 코드 | 1,176 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/ExecutionPipeline.kt` | 기타 애플리케이션 코드 | 15,251 | 408 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/ExecutionPolicy.kt` | 기타 애플리케이션 코드 | 3,391 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/FailureMode.kt` | 기타 애플리케이션 코드 | 1,465 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/FinallyPolicy.kt` | 기타 애플리케이션 코드 | 3,316 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/LoggingPolicy.kt` | 기타 애플리케이션 코드 | 2,506 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/PolicyOrder.kt` | 기타 애플리케이션 코드 | 2,166 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/TaskLogSupport.kt` | 기타 애플리케이션 코드 | 1,789 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/TaskLogTags.kt` | 기타 애플리케이션 코드 | 998 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/strategy/ExceptionTranslator.kt` | 기타 애플리케이션 코드 | 5,482 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/NexonApiClient.kt` | 기타 애플리케이션 코드 | 1,742 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/NexonAuthClient.kt` | 기타 애플리케이션 코드 | 810 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/config/ExternalApiMetricsFilter.kt` | 기타 애플리케이션 코드 | 7,577 | 210 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/config/NexonApiMetricsConfig.kt` | 기타 애플리케이션 코드 | 2,007 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/CharacterBasicResponse.kt` | 기타 애플리케이션 코드 | 799 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/CharacterListResponse.kt` | 기타 애플리케이션 코드 | 1,544 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/CharacterOcidResponse.kt` | 기타 애플리케이션 코드 | 206 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/CubeHistoryResponse.kt` | 기타 애플리케이션 코드 | 1,696 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/EquipmentResponse.kt` | 기타 애플리케이션 코드 | 8,419 | 265 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/TotalExpectationResponse.kt` | 기타 애플리케이션 코드 | 1,243 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/AlertNotificationHelper.kt` | 기타 애플리케이션 코드 | 3,144 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/FallbackHandler.kt` | 기타 애플리케이션 코드 | 9,505 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/MetricsNexonApiClientWrapper.kt` | 기타 애플리케이션 코드 | 8,207 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/NexonApiClientConfig.kt` | 기타 애플리케이션 코드 | 3,092 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/PgmqFallbackPublisher.kt` | 기타 애플리케이션 코드 | 4,333 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/RealNexonApiClient.kt` | 기타 애플리케이션 코드 | 5,378 | 136 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/RealNexonAuthClient.kt` | 기타 애플리케이션 코드 | 4,217 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/ResilientNexonApiClient.kt` | 기타 애플리케이션 코드 | 11,006 | 278 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/package-info.kt` | 기타 애플리케이션 코드 | 391 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapter.kt` | 기타 애플리케이션 코드 | 2,241 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/fanout/NexonFanOutBatchLoader.kt` | 기타 애플리케이션 코드 | 3,628 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/hotkey/HotKeyDetector.kt` | 기타 애플리케이션 코드 | 4,959 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/hotkey/HotKeyDistributor.kt` | 기타 애플리케이션 코드 | 6,207 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/jdbc/JdbcBatchUpsertRepository.kt` | 기타 애플리케이션 코드 | 14,804 | 397 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/jdbc/config/JdbcBatchRetryConfig.kt` | 기타 애플리케이션 코드 | 2,086 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt` | 기타 애플리케이션 코드 | 4,001 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationDispatchService.kt` | 기타 애플리케이션 코드 | 5,051 | 111 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt` | 기타 애플리케이션 코드 | 11,833 | 332 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` | 기타 애플리케이션 코드 | 3,275 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt` | 기타 애플리케이션 코드 | 4,004 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt` | 기타 애플리케이션 코드 | 2,459 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt` | 기타 애플리케이션 코드 | 1,494 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/SnapshotCleanupWorker.kt` | 기타 애플리케이션 코드 | 1,982 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/GracefulShutdownHook.kt` | 기타 애플리케이션 코드 | 4,523 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ManagedLifecycle.kt` | 기타 애플리케이션 코드 | 527 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ManagedLifecycleCoordinator.kt` | 기타 애플리케이션 코드 | 2,532 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ScheduledTaskLifecycleWrapper.kt` | 기타 애플리케이션 코드 | 3,287 | 96 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/ShutdownCoordinator.kt` | 기타 애플리케이션 코드 | 3,682 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lifecycle/VirtualThreadExecutorManager.kt` | 기타 애플리케이션 코드 | 1,160 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/AbstractLockStrategy.kt` | 기타 애플리케이션 코드 | 6,930 | 184 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/GuavaLockStrategy.kt` | 기타 애플리케이션 코드 | 4,922 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LeaderElectionStrategy.kt` | 기타 애플리케이션 코드 | 2,529 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockFallbackMetrics.kt` | 기타 애플리케이션 코드 | 3,435 | 102 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockMetrics.kt` | 기타 애플리케이션 코드 | 3,612 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockOrderMetrics.kt` | 기타 애플리케이션 코드 | 2,846 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategy.kt` | 기타 애플리케이션 코드 | 3,147 | 74 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategyConfiguration.kt` | 기타 애플리케이션 코드 | 1,155 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutor.kt` | 기타 애플리케이션 코드 | 19,919 | 514 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategy.kt` | 기타 애플리케이션 코드 | 18,965 | 461 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresLockStrategy.kt` | 기타 애플리케이션 코드 | 20,277 | 522 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/KafkaEventPublisher.kt` | 기타 애플리케이션 코드 | 2,703 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/PgmqEventPublisherAdapter.kt` | 기타 애플리케이션 코드 | 2,568 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/ForkJoinPoolMetrics.kt` | 기타 애플리케이션 코드 | 1,906 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/QueueMetrics.kt` | 기타 애플리케이션 코드 | 591 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/MonitoringAlertService.kt` | 기타 애플리케이션 코드 | 3,682 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/AiAnalysisFormatter.kt` | 기타 애플리케이션 코드 | 4,230 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/AiAnalysisPortAdapter.kt` | 기타 애플리케이션 코드 | 3,174 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/AiPromptBuilder.kt` | 기타 애플리케이션 코드 | 8,171 | 231 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/AiResponseParser.kt` | 기타 애플리케이션 코드 | 5,375 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/AiSreService.kt` | 기타 애플리케이션 코드 | 9,154 | 245 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/NoOpAiSreService.kt` | 기타 애플리케이션 코드 | 925 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/RuleBasedAnalyzer.kt` | 기타 애플리케이션 코드 | 3,111 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/config/OpenAIConfiguration.kt` | 기타 애플리케이션 코드 | 1,795 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/config/ZAiConfiguration.kt` | 기타 애플리케이션 코드 | 1,923 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/alert/AlertNotificationPortAdapter.kt` | 기타 애플리케이션 코드 | 1,971 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/anomaly/AnomalyDetectionPortAdapter.kt` | 기타 애플리케이션 코드 | 3,283 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/CircuitBreakerEventLogger.kt` | 기타 애플리케이션 코드 | 2,198 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/CircuitBreakerMetricsCollector.kt` | 기타 애플리케이션 코드 | 2,128 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/DatabaseMetricsCollector.kt` | 기타 애플리케이션 코드 | 11,062 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/GoldenSignalsCollector.kt` | 기타 애플리케이션 코드 | 3,783 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/JvmMetricsCollector.kt` | 기타 애플리케이션 코드 | 2,949 | 89 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/MetricCategory.kt` | 기타 애플리케이션 코드 | 1,453 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/MetricsCollectorStrategy.kt` | 기타 애플리케이션 코드 | 1,315 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/collector/MetricsCollectorUtils.kt` | 기타 애플리케이션 코드 | 1,605 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/context/SystemContextProvider.kt` | 기타 애플리케이션 코드 | 5,226 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/client/PrometheusClient.kt` | 기타 애플리케이션 코드 | 6,429 | 164 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/client/QueryEvaluation.kt` | 기타 애플리케이션 코드 | 824 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/config/MonitoringCopilotConfig.kt` | 기타 애플리케이션 코드 | 3,373 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/dedup/SignalDeduplicationStrategy.kt` | 기타 애플리케이션 코드 | 1,630 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/dedup/TimeBasedSlidingWindowStrategy.kt` | 기타 애플리케이션 코드 | 5,312 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/detector/AnomalyDetector.kt` | 기타 애플리케이션 코드 | 8,362 | 249 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/ingestor/GrafanaJsonIngestor.kt` | 기타 애플리케이션 코드 | 8,159 | 229 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/Action.kt` | 기타 애플리케이션 코드 | 476 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/AnomalyEvent.kt` | 기타 애플리케이션 코드 | 373 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/AnomalySeverity.kt` | 기타 애플리케이션 코드 | 284 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/EvidenceItem.kt` | 기타 애플리케이션 코드 | 224 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/Hypothesis.kt` | 기타 애플리케이션 코드 | 231 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/IncidentContext.kt` | 기타 애플리케이션 코드 | 399 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/MetricPoint.kt` | 기타 애플리케이션 코드 | 630 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/MitigationPlan.kt` | 기타 애플리케이션 코드 | 388 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/RichEvidence.kt` | 기타 애플리케이션 코드 | 1,263 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/SeverityMapping.kt` | 기타 애플리케이션 코드 | 431 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/SignalDefinition.kt` | 기타 애플리케이션 코드 | 883 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/TimeSeries.kt` | 기타 애플리케이션 코드 | 285 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/model/ZScoreConfig.kt` | 기타 애플리케이션 코드 | 1,236 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/notifier/DiscordNotifier.kt` | 기타 애플리케이션 코드 | 5,784 | 161 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/AlertNotificationService.kt` | 기타 애플리케이션 코드 | 5,681 | 154 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/AnomalyDetectionOrchestrator.kt` | 기타 애플리케이션 코드 | 6,220 | 167 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/DeDuplicationCache.kt` | 기타 애플리케이션 코드 | 2,246 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/SignalDefinitionLoader.kt` | 기타 애플리케이션 코드 | 2,770 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/scheduler/MonitoringCopilotScheduler.kt` | 기타 애플리케이션 코드 | 3,965 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/prometheus/MetricsQueryPortAdapter.kt` | 기타 애플리케이션 코드 | 1,237 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/security/PiiMaskingFilter.kt` | 기타 애플리케이션 코드 | 2,761 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/NexonApiRequestEventFactory.kt` | 기타 애플리케이션 코드 | 604 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/NexonApiResponseEventFactory.kt` | 기타 애플리케이션 코드 | 667 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/OcidResolveEventFactory.kt` | 기타 애플리케이션 코드 | 476 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/ResultReadyEventFactory.kt` | 기타 애플리케이션 코드 | 806 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/LegacyMessageAdapter.kt` | 기타 애플리케이션 코드 | 1,254 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqEventAppender.kt` | 기타 애플리케이션 코드 | 535 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqTopicConfig.kt` | 기타 애플리케이션 코드 | 179 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/PgmqTopicGroup.kt` | 기타 애플리케이션 코드 | 4,504 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/NexonApiRequestTopic.kt` | 기타 애플리케이션 코드 | 1,001 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/NexonApiResponseTopic.kt` | 기타 애플리케이션 코드 | 1,004 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/OcidResolveTopic.kt` | 기타 애플리케이션 코드 | 993 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/ResultReadyTopic.kt` | 기타 애플리케이션 코드 | 1,052 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/pgmq/NexonApiPgmqMetrics.kt` | 기타 애플리케이션 코드 | 3,277 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/pgmq/NexonApiPgmqProcessor.kt` | 기타 애플리케이션 코드 | 11,562 | 287 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/nexon/util/ContentHashUtil.kt` | 기타 애플리케이션 코드 | 1,401 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/notification/discord/DiscordAlertService.kt` | 기타 애플리케이션 코드 | 3,784 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/notification/discord/DiscordMessageFactory.kt` | 기타 애플리케이션 코드 | 5,182 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/notification/discord/dto/DiscordMessage.kt` | 기타 애플리케이션 코드 | 1,422 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterEquipmentJpaRepository.kt` | 기타 애플리케이션 코드 | 910 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryPortAdapter.kt` | 기타 애플리케이션 코드 | 4,124 | 101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt` | 기타 애플리케이션 코드 | 23,076 | 482 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteService.kt` | 기타 애플리케이션 코드 | 4,625 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/PostgresPersistenceTrackerAdapter.kt` | 기타 애플리케이션 코드 | 2,119 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationJobEntity.kt` | 기타 애플리케이션 코드 | 1,281 | 58 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationResultEntity.kt` | 기타 애플리케이션 코드 | 1,215 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotEntity.kt` | 기타 애플리케이션 코드 | 864 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotInputEntity.kt` | 기타 애플리케이션 코드 | 758 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CharacterEquipmentJpaEntity.kt` | 기타 애플리케이션 코드 | 2,932 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CharacterLikeJpaEntity.kt` | 기타 애플리케이션 코드 | 2,208 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CharacterValuationEntity.kt` | 기타 애플리케이션 코드 | 4,550 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CharacterValuationViewEntity.kt` | 기타 애플리케이션 코드 | 3,519 | 114 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CubeProbability.kt` | 기타 애플리케이션 코드 | 655 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/DonationHistoryEntity.kt` | 기타 애플리케이션 코드 | 1,636 | 64 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/EquipmentExpectationSummaryEntity.kt` | 기타 애플리케이션 코드 | 4,842 | 147 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/ExpectationReadModelEntity.kt` | 기타 애플리케이션 코드 | 1,224 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/GameCharacterJpaEntity.kt` | 기타 애플리케이션 코드 | 4,079 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/GameCharacterV2Entity.kt` | 기타 애플리케이션 코드 | 2,579 | 90 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/MemberEntity.kt` | 기타 애플리케이션 코드 | 1,191 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/NexonRawDataEntity.kt` | 기타 애플리케이션 코드 | 3,121 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/OutboxEventEntity.kt` | 기타 애플리케이션 코드 | 894 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/CharacterLikeJpaRepository.kt` | 기타 애플리케이션 코드 | 3,773 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/CharacterValuationJpaRepository.kt` | 기타 애플리케이션 코드 | 2,778 | 79 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepository.kt` | 기타 애플리케이션 코드 | 3,678 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustom.kt` | 기타 애플리케이션 코드 | 553 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustomImpl.kt` | 기타 애플리케이션 코드 | 1,035 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/MemberJpaRepository.kt` | 기타 애플리케이션 코드 | 2,616 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/NexonRawDataJpaRepository.kt` | 기타 애플리케이션 코드 | 2,290 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/mapper/CharacterEquipmentMapper.kt` | 기타 애플리케이션 코드 | 4,560 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/package-info.kt` | 기타 애플리케이션 코드 | 404 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt` | 기타 애플리케이션 코드 | 6,830 | 220 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationResultRepository.kt` | 기타 애플리케이션 코드 | 2,155 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotInputRepository.kt` | 기타 애플리케이션 코드 | 1,219 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotRepository.kt` | 기타 애플리케이션 코드 | 495 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterEquipmentRepository.kt` | 기타 애플리케이션 코드 | 3,369 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterEquipmentRepositoryImpl.kt` | 기타 애플리케이션 코드 | 3,120 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterLikeRepository.kt` | 기타 애플리케이션 코드 | 6,942 | 177 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterLikeRepositoryImpl.kt` | 기타 애플리케이션 코드 | 2,936 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationRepositoryImpl.kt` | 기타 애플리케이션 코드 | 6,879 | 196 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterValuationViewJpaRepository.kt` | 기타 애플리케이션 코드 | 900 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterViewBatchRepository.kt` | 기타 애플리케이션 코드 | 5,705 | 157 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CubeProbabilityRepository.kt` | 기타 애플리케이션 코드 | 1,897 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CubeProbabilityRepositoryImpl.kt` | 기타 애플리케이션 코드 | 4,413 | 117 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/DonationHistoryRepository.kt` | 기타 애플리케이션 코드 | 431 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/EquipmentExpectationSummaryBatchRepository.kt` | 기타 애플리케이션 코드 | 8,373 | 220 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/EquipmentExpectationSummaryRepository.kt` | 기타 애플리케이션 코드 | 941 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationBatchRepository.kt` | 기타 애플리케이션 코드 | 5,443 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationReadModelRepository.kt` | 기타 애플리케이션 코드 | 1,415 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/GameCharacterRepository.kt` | 기타 애플리케이션 코드 | 5,848 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/GameCharacterRepositoryImpl.kt` | 기타 애플리케이션 코드 | 3,837 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepository.kt` | 기타 애플리케이션 코드 | 6,195 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/MemberRepositoryImpl.kt` | 기타 애플리케이션 코드 | 1,931 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/NexonCharacterRepository.kt` | 기타 애플리케이션 코드 | 968 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/NexonCharacterRepositoryCustom.kt` | 기타 애플리케이션 코드 | 1,157 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/NexonCharacterRepositoryImpl.kt` | 기타 애플리케이션 코드 | 3,633 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/NexonRawDataStore.kt` | 기타 애플리케이션 코드 | 4,589 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/OutboxEventRepository.kt` | 기타 애플리케이션 코드 | 1,856 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/worker/EquipmentDbWorker.kt` | 기타 애플리케이션 코드 | 6,813 | 175 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/AccumulationBuffer.kt` | 기타 애플리케이션 코드 | 1,359 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/CalculationCompletedPayload.kt` | 기타 애플리케이션 코드 | 415 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/CalculationRequestedPayload.kt` | 기타 애플리케이션 코드 | 221 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/CalculationResult.kt` | 기타 애플리케이션 코드 | 258 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt` | 기타 애플리케이션 코드 | 9,093 | 244 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ExternalApiJobPayload.kt` | 기타 애플리케이션 코드 | 154 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqClient.kt` | 기타 애플리케이션 코드 | 20,174 | 522 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqConfig.kt` | 기타 애플리케이션 코드 | 2,453 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqMessage.kt` | 기타 애플리케이션 코드 | 4,570 | 177 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` | 기타 애플리케이션 코드 | 21,083 | 538 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt` | 기타 애플리케이션 코드 | 3,054 | 92 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerMetrics.kt` | 기타 애플리케이션 코드 | 568 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PipelineBuffer.kt` | 기타 애플리케이션 코드 | 1,388 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcome.kt` | 기타 애플리케이션 코드 | 1,251 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/WorkerQueueMetrics.kt` | 기타 애플리케이션 코드 | 6,127 | 164 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/postgres/admin/AdminFingerprintRepository.kt` | 기타 애플리케이션 코드 | 3,674 | 123 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/postgres/fallback/FallbackCacheRepository.kt` | 기타 애플리케이션 코드 | 4,155 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/postgres/warmup/PopularCharacterAccessRepository.kt` | 기타 애플리케이션 코드 | 5,409 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/provider/EquipmentDataProvider.kt` | 기타 애플리케이션 코드 | 4,415 | 107 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/provider/EquipmentFetchProvider.kt` | 기타 애플리케이션 코드 | 3,948 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/MessageQueueStrategy.kt` | 기타 애플리케이션 코드 | 4,872 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/QueueMessage.kt` | 기타 애플리케이션 코드 | 2,125 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/QueueNames.kt` | 기타 애플리케이션 코드 | 1,228 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/QueueType.kt` | 기타 애플리케이션 코드 | 2,684 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/LikeSyncExecutor.kt` | 기타 애플리케이션 코드 | 2,201 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/event/LikeSyncFailedEvent.kt` | 기타 애플리케이션 코드 | 1,574 | 55 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/CalculationQueueProducer.kt` | 기타 애플리케이션 코드 | 3,112 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/DonationQueueProducer.kt` | 기타 애플리케이션 코드 | 3,072 | 106 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/FanOutQueueProducer.kt` | 기타 애플리케이션 코드 | 1,957 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/NexonApiRequestMessage.kt` | 기타 애플리케이션 코드 | 324 | 12 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/NexonApiResponseMessage.kt` | 기타 애플리케이션 코드 | 312 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/NexonDataQueueProducer.kt` | 기타 애플리케이션 코드 | 2,640 | 88 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/OcidResolveMessage.kt` | 기타 애플리케이션 코드 | 251 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/strategy/InMemoryBufferStrategy.kt` | 기타 애플리케이션 코드 | 6,668 | 186 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/ConsumeResult.kt` | 기타 애플리케이션 코드 | 1,714 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/NexonRateLimiter.kt` | 기타 애플리케이션 코드 | 2,621 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/PostgresRateLimiter.kt` | 기타 애플리케이션 코드 | 10,131 | 298 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/RateLimitContext.kt` | 기타 애플리케이션 코드 | 1,262 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/RateLimiter.kt` | 기타 애플리케이션 코드 | 892 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/RateLimitingFacade.kt` | 기타 애플리케이션 코드 | 3,241 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/RateLimitingService.kt` | 기타 애플리케이션 코드 | 2,174 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/config/RateLimitProperties.kt` | 기타 애플리케이션 코드 | 4,168 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/exception/RateLimitExceededException.kt` | 기타 애플리케이션 코드 | 1,550 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/filter/RateLimitingFilter.kt` | 기타 애플리케이션 코드 | 4,689 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/strategy/AbstractBucket4jRateLimiter.kt` | 기타 애플리케이션 코드 | 6,081 | 187 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/strategy/IpBasedRateLimiter.kt` | 기타 애플리케이션 코드 | 1,899 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/ratelimit/strategy/UserBasedRateLimiter.kt` | 기타 애플리케이션 코드 | 1,967 | 65 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/resilience/RetryBudgetManager.kt` | 기타 애플리케이션 코드 | 4,455 | 137 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/resilience/RetryBudgetProperties.kt` | 기타 애플리케이션 코드 | 2,012 | 79 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/BatchWriter.kt` | 기타 애플리케이션 코드 | 4,413 | 118 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/ExpectationCalculationScheduler.kt` | 기타 애플리케이션 코드 | 3,570 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/NexonApiCollectorScheduler.kt` | 기타 애플리케이션 코드 | 8,000 | 225 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/NexonDataCollectionScheduler.kt` | 기타 애플리케이션 코드 | 3,190 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/PgmqArchiveCleanupScheduler.kt` | 기타 애플리케이션 코드 | 1,987 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/PopularCharacterWarmupScheduler.kt` | 기타 애플리케이션 코드 | 5,549 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/AccountIdGenerator.kt` | 기타 애플리케이션 코드 | 1,452 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/FingerprintGenerator.kt` | 기타 애플리케이션 코드 | 1,394 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/config/SecurityConfig.kt` | 기타 애플리케이션 코드 | 4,073 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/JwtAuthenticationFilter.kt` | 기타 애플리케이션 코드 | 9,519 | 230 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/PathVariableValidationFilter.kt` | 기타 애플리케이션 코드 | 2,192 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/PrometheusSecurityFilter.kt` | 기타 애플리케이션 코드 | 7,830 | 237 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtPayload.kt` | 기타 애플리케이션 코드 | 117 | 3 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtTokenProvider.kt` | 기타 애플리케이션 코드 | 11,706 | 294 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/shutdown/ShutdownProperties.kt` | 기타 애플리케이션 코드 | 1,262 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/shutdown/dto/FlushResult.kt` | 기타 애플리케이션 코드 | 754 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/shutdown/dto/ShutdownData.kt` | 기타 애플리케이션 코드 | 974 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorage.kt` | 기타 애플리케이션 코드 | 6,912 | 164 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt` | 기타 애플리케이션 코드 | 1,456 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` | 기타 애플리케이션 코드 | 15,251 | 331 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioProperties.kt` | 기타 애플리케이션 코드 | 798 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/StorageConfig.kt` | 기타 애플리케이션 코드 | 6,638 | 148 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/util/AsyncUtils.kt` | 기타 애플리케이션 코드 | 5,146 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/util/JsonMapper.kt` | 기타 애플리케이션 코드 | 2,235 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/util/PermutationUtil.kt` | 기타 애플리케이션 코드 | 743 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt` | 기타 애플리케이션 코드 | 9,006 | 192 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationCompletedWorker.kt` | 기타 애플리케이션 코드 | 6,374 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationRequestedWorker.kt` | 기타 애플리케이션 코드 | 7,988 | 172 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationWorker.kt` | 기타 애플리케이션 코드 | 4,713 | 116 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/DonationWorker.kt` | 기타 애플리케이션 코드 | 4,829 | 114 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcLowWorker.kt` | 기타 애플리케이션 코드 | 2,552 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcWorker.kt` | 기타 애플리케이션 코드 | 2,545 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt` | 기타 애플리케이션 코드 | 24,370 | 487 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt` | 기타 애플리케이션 코드 | 5,196 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonFanOutWorker.kt` | 기타 애플리케이션 코드 | 5,756 | 134 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OcidResolveWorker.kt` | 기타 애플리케이션 코드 | 3,703 | 87 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OutboxRelayWorker.kt` | 기타 애플리케이션 코드 | 2,524 | 59 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt` | 기타 애플리케이션 코드 | 9,453 | 224 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/kotlin/maple/expectation/util/converter/GzipStringConverter.kt` | 기타 애플리케이션 코드 | 1,774 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 설정·기타 | 70 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/data/cube_probability.csv` | 설정·기타 | 22,692,657 | 413803 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V100__like_postgres_migration.sql` | 기타 애플리케이션 코드 | 2,284 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V101__donation_postgres_migration.sql` | 기타 애플리케이션 코드 | 2,163 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V102__load_test_index_optimization.sql` | 기타 애플리케이션 코드 | 7,238 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V103__equipment_persistence_tracker.sql` | 기타 애플리케이션 코드 | 660 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V103__like_fingerprint_account_id.sql` | 기타 애플리케이션 코드 | 1,287 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V104__expectation_calc_pgmq.sql` | 기타 애플리케이션 코드 | 204 | 4 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V104__like_count_trigger.sql` | 기타 애플리케이션 코드 | 2,715 | 70 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V105__nexon_fanout_queue.sql` | 기타 애플리케이션 코드 | 277 | 4 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V105__nexon_retry_pgmq.sql` | 기타 애플리케이션 코드 | 251 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V106__drop_outbox_tables.sql` | 기타 애플리케이션 코드 | 322 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V107__cache_evict_range_query.sql` | 기타 애플리케이션 코드 | 697 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V108__dlq_replay_tracking.sql` | 기타 애플리케이션 코드 | 724 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V109__rate_limit_table.sql` | 기타 애플리케이션 코드 | 452 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V110__cache_storage_create_table.sql` | 기타 애플리케이션 코드 | 440 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V111__create_expectation_read_model.sql` | 기타 애플리케이션 코드 | 1,218 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V112__pgmq_dedup_index_and_monotonic_upsert.sql` | 기타 애플리케이션 코드 | 1,931 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V113__add_preset_no_to_views.sql` | 기타 애플리케이션 코드 | 463 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V114__external_api_boundary.sql` | 기타 애플리케이션 코드 | 2,433 | 62 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V115__ocid_resolve_pipeline.sql` | 기타 애플리케이션 코드 | 1,025 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V116__fix_active_dedup_index.sql` | 기타 애플리케이션 코드 | 478 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V117__write_path_tables.sql` | 기타 애플리케이션 코드 | 1,995 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V118__codex_review_fixes.sql` | 기타 애플리케이션 코드 | 392 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V119__widen_equipment_persistence_tracker_ocid.sql` | 기타 애플리케이션 코드 | 310 | 4 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V120__calculation_job_request_key_dedup.sql` | 기타 애플리케이션 코드 | 1,341 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V121__split_calculation_pipeline_queues.sql` | 기타 애플리케이션 코드 | 361 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V122__calculation_results_projection_fields.sql` | 기타 애플리케이션 코드 | 740 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V123__synchronizer_read_model_tables.sql` | 기타 애플리케이션 코드 | 1,427 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V124__read_model_document_hash.sql` | 기타 애플리케이션 코드 | 223 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V125__read_model_add_user_ign.sql` | 기타 애플리케이션 코드 | 614 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V126__character_basic_read_model.sql` | 기타 애플리케이션 코드 | 661 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V127__equipment_total_cost_ranking_index.sql` | 기타 애플리케이션 코드 | 190 | 4 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/db/migration/V128__chunk_execution.sql` | 현재 스키마 | 1,470 | 52 | 수동 심층 검토+교차검증 |
| `module-infra/src/main/resources/lua/event/coalesce_add.lua` | 설정·기타 | 2,033 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/lua/event/rate_limit_check.lua` | 설정·기타 | 2,445 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/main/resources/maple-infra-defaults.properties` | 빌드·배포·설정 | 2,066 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/java/maple/expectation/infrastructure/executor/LogicExecutorTest.java` | 테스트 | 26,906 | 771 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/java/maple/expectation/infrastructure/like/LikeToggleServiceTest.java` | 테스트 | 10,206 | 235 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt` | 테스트 | 2,648 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/admission/GlobalAdmissionControlTest.kt` | 테스트 | 12,051 | 301 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/aop/aspect/NexonDataCacheAspectTest.kt` | 테스트 | 2,316 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserServiceTest.kt` | 테스트 | 12,833 | 382 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/batch/DedupeMicroBatchWriterTest.kt` | 테스트 | 10,849 | 304 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/batch/GameCharacterMicroBatchAdapterTest.kt` | 테스트 | 5,834 | 158 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/batch/L2CacheMicroBatchAdapterTest.kt` | 테스트 | 5,880 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorageTest.kt` | 테스트 | 4,757 | 142 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuardTest.kt` | 테스트 | 1,115 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiterTest.kt` | 테스트 | 1,346 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphoreTest.kt` | 테스트 | 1,269 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelectorTest.kt` | 테스트 | 1,170 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponentTest.kt` | 테스트 | 819 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncherTest.kt` | 테스트 | 674 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverterTest.kt` | 테스트 | 3,715 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/executor/classifier/ExceptionClassifierTest.kt` | 테스트 | 6,306 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/impl/MetricsNexonApiClientWrapperTest.kt` | 테스트 | 5,484 | 126 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/external/snapshot/SnapshotObjectStoreAdapterTest.kt` | 테스트 | 2,320 | 66 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/fanout/NexonFanOutBatchLoaderTest.kt` | 테스트 | 6,420 | 156 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestratorTest.kt` | 테스트 | 6,479 | 160 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationDispatchServiceTest.kt` | 테스트 | 8,681 | 209 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationExecutionServiceTest.kt` | 테스트 | 3,101 | 93 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt` | 테스트 | 1,474 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestratorTest.kt` | 테스트 | 4,711 | 130 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutorAsyncTest.kt` | 테스트 | 4,024 | 100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategyAsyncTest.kt` | 테스트 | 5,171 | 128 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryPortAdapterTest.kt` | 테스트 | 11,548 | 322 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgresTest.kt` | 테스트 | 16,036 | 438 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteServiceTest.kt` | 테스트 | 7,481 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/JpaNPlusOneRegressionTest.kt` | 테스트 | 4,860 | 112 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/AccumulationBufferTest.kt` | 테스트 | 4,081 | 105 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqClientBatchArchiveTest.kt` | 테스트 | 5,324 | 145 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqTestSupport.kt` | 테스트 | 8,923 | 270 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerPipelineTest.kt` | 테스트 | 4,939 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerProcessAsyncTest.kt` | 테스트 | 5,000 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerSequentialTest.kt` | 테스트 | 1,472 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PipelineBufferTest.kt` | 테스트 | 5,053 | 154 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcomeTest.kt` | 테스트 | 1,886 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/queue/pgmq/CalculationQueueProducerIntegrationTest.kt` | 테스트 | 11,646 | 358 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/queue/pgmq/DonationQueueProducerIntegrationTest.kt` | 테스트 | 13,725 | 426 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/CalculatorBootSmokeIT.kt` | 테스트 | 1,558 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/CleanupBootSmokeIT.kt` | 테스트 | 1,546 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/ExtApiBootSmokeIT.kt` | 테스트 | 1,545 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsObjectStorageTest.kt` | 테스트 | 5,058 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/LocalFsPutStreamMultipartTest.kt` | 테스트 | 2,707 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicatorTest.kt` | 테스트 | 1,508 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorageIT.kt` | 테스트 | 7,090 | 179 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyJsonTest.kt` | 테스트 | 6,044 | 141 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/MinioPolicyScopeIT.kt` | 테스트 | 10,075 | 219 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/StorageConfigTest.kt` | 테스트 | 1,147 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/storage/SynchronizerBootSmokeIT.kt` | 테스트 | 1,566 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/test/LockBlockingPrimitiveGateTest.kt` | 테스트 | 7,156 | 176 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/test/PgmqBlockingPrimitiveGateTest.kt` | 테스트 | 5,252 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/CalculationCompletedWorkerAsyncTest.kt` | 테스트 | 8,462 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/CalculationRequestedWorkerAsyncTest.kt` | 테스트 | 8,703 | 206 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/CalculationWorkerAsyncTest.kt` | 테스트 | 4,024 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/CalculationWorkerIntegrationTest.kt` | 테스트 | 20,004 | 569 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/DonationWorkerAsyncTest.kt` | 테스트 | 6,608 | 163 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorkerAsyncTest.kt` | 테스트 | 7,955 | 200 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/NexonFanOutWorkerAsyncTest.kt` | 테스트 | 6,758 | 162 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorkerAsyncTest.kt` | 테스트 | 6,986 | 172 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/test/DatabaseCleaner.kt` | 테스트 | 2,372 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/test/InfraAdapterTestTemplate.kt` | 테스트 | 3,869 | 127 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/test/InfraTestConfiguration.kt` | 테스트 | 909 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/test/README.md` | 테스트 | 6,912 | 283 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/kotlin/maple/expectation/test/ServiceIntegrationTestBase.kt` | 테스트 | 2,130 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-infra/src/test/resources/sql/init-pgmq.sql` | 테스트 | 273 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/build.gradle` | 빌드·배포·설정 | 1,760 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/RestControllerApplication.kt` | 현재 파이프라인 코드 | 419 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/advice/RestControllerExceptionHandler.kt` | 현재 파이프라인 코드 | 1,621 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JdbcOcidQueryAdapter.kt` | 현재 파이프라인 코드 | 1,868 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt` | 현재 파이프라인 코드 | 2,665 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtParserAdapter.kt` | 현재 파이프라인 코드 | 2,213 | 60 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/config/RestControllerExecutorConfig.kt` | 현재 파이프라인 코드 | 842 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt` | 현재 파이프라인 코드 | 5,493 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadProperties.kt` | 현재 파이프라인 코드 | 1,106 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/config/WebMvcConfig.kt` | 현재 파이프라인 코드 | 636 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/EquipmentRankingController.kt` | 현재 파이프라인 코드 | 910 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` | 현재 파이프라인 코드 | 4,465 | 100 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/HealthController.kt` | 현재 파이프라인 코드 | 297 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/PopularCharacterController.kt` | 현재 파이프라인 코드 | 904 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/AuthController.kt` | 현재 파이프라인 코드 | 1,974 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/LikeController.kt` | 현재 파이프라인 코드 | 2,244 | 57 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/like/JdbcLikeToggleService.kt` | 현재 파이프라인 코드 | 3,652 | 85 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/metrics/V6ReadMetrics.kt` | 현재 파이프라인 코드 | 3,083 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/popular/PopularCharacterModels.kt` | 현재 파이프라인 코드 | 392 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/popular/PopularCharacterService.kt` | 현재 파이프라인 코드 | 2,805 | 75 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/popular/adapter/out/PopularCharacterRedisAdapter.kt` | 현재 파이프라인 코드 | 3,154 | 76 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/popular/port/out/PopularCharacterRedisPort.kt` | 현재 파이프라인 코드 | 1,090 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/popular/port/out/PopularCharacterScoreEntry.kt` | 현재 파이프라인 코드 | 290 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/ranking/EquipmentRankingCacheService.kt` | 현재 파이프라인 코드 | 1,605 | 40 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/ranking/EquipmentRankingModels.kt` | 현재 파이프라인 코드 | 374 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/ranking/EquipmentRankingQueryService.kt` | 현재 파이프라인 코드 | 1,085 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/ranking/EquipmentRankingService.kt` | 현재 파이프라인 코드 | 839 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt` | 현재 파이프라인 코드 | 5,398 | 139 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolveResult.kt` | 현재 파이프라인 코드 | 1,741 | 51 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` | 현재 코드 | 4,816 | 118 | 수동 심층 검토+교차검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResponseMapper.kt` | 현재 파이프라인 코드 | 1,445 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/EnqueueResult.kt` | 현재 파이프라인 코드 | 697 | 19 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt` | 현재 파이프라인 코드 | 3,565 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadResponseMapper.kt` | 현재 파이프라인 코드 | 1,001 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/InflightRequestRegistry.kt` | 현재 파이프라인 코드 | 1,472 | 39 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/LocalRequestBuffer.kt` | 현재 파이프라인 코드 | 1,344 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/NegativeCacheService.kt` | 현재 파이프라인 코드 | 1,464 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt` | 현재 코드 | 3,824 | 99 | 수동 심층 검토+교차검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelDocumentExtractor.kt` | 현재 파이프라인 코드 | 1,777 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt` | 현재 파이프라인 코드 | 2,201 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelRowQuery.kt` | 현재 파이프라인 코드 | 960 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadRequest.kt` | 현재 파이프라인 코드 | 181 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/RequestBuffer.kt` | 현재 파이프라인 코드 | 252 | 10 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/StalenessCheck.kt` | 현재 파이프라인 코드 | 638 | 20 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/UrgentDedupService.kt` | 현재 파이프라인 코드 | 3,906 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/UrgentReadStatus.kt` | 현재 파이프라인 코드 | 2,263 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/V6ExpectationResponse.kt` | 현재 파이프라인 코드 | 308 | 13 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterNotFoundConsumer.kt` | 현재 파이프라인 코드 | 1,628 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentCharacterRequest.kt` | 현재 파이프라인 코드 | 276 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisher.kt` | 현재 파이프라인 코드 | 1,164 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/validation/UserIgnValidator.kt` | 현재 파이프라인 코드 | 580 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/validation/ValidUserIgn.kt` | 현재 파이프라인 코드 | 526 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/resources/application-local.yml` | 현재 파이프라인 코드 | 384 | 21 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/resources/application-prod.yml` | 현재 파이프라인 코드 | 214 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/main/resources/application.yml` | 현재 파이프라인 코드 | 2,697 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt` | 테스트 | 3,918 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/ranking/EquipmentRankingServiceTest.kt` | 테스트 | 1,657 | 47 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt` | 테스트 | 4,582 | 122 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/InflightRequestRegistryTest.kt` | 테스트 | 3,121 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/LocalRequestBufferTest.kt` | 테스트 | 2,569 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelDocumentExtractorTest.kt` | 테스트 | 2,946 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelQueryServiceTest.kt` | 테스트 | 6,647 | 168 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelRowQueryTest.kt` | 테스트 | 1,626 | 46 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/StalenessCheckTest.kt` | 테스트 | 1,763 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/UrgentReadStateTest.kt` | 테스트 | 4,094 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/urgent/UrgentCharacterNotFoundConsumerTest.kt` | 테스트 | 1,326 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/urgent/UrgentTriggerPublisherTest.kt` | 테스트 | 1,636 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/build.gradle` | 빌드·배포·설정 | 1,536 | 60 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt` | 현재 파이프라인 코드 | 1,264 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt` | 현재 코드 | 2,253 | 53 | 수동 심층 검토+교차검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/builder/EquipmentDocumentBuilder.kt` | 현재 파이프라인 코드 | 1,962 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerReaderConfig.kt` | 현재 파이프라인 코드 | 416 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` | 현재 파이프라인 코드 | 2,037 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt` | 현재 코드 | 10,543 | 298 | 수동 심층 검토+교차검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkExecutionProperties.kt` | 현재 파이프라인 코드 | 1,118 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkLifecycleEvent.kt` | 현재 파이프라인 코드 | 651 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt` | 현재 파이프라인 코드 | 6,653 | 147 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` | 현재 파이프라인 코드 | 2,337 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/domain/CalculatedEquipmentItem.kt` | 현재 파이프라인 코드 | 543 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/domain/ChunkDomainReexport.kt` | 현재 파이프라인 코드 | 365 | 6 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/event/KafkaChunkConsumedEventPublisher.kt` | 현재 파이프라인 코드 | 1,375 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilder.kt` | 현재 파이프라인 코드 | 277 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/ChunkExecutionMetrics.kt` | 현재 파이프라인 코드 | 1,652 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/DocumentVolumeMetrics.kt` | 현재 파이프라인 코드 | 1,613 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerChunkMetricsListener.kt` | 현재 파이프라인 코드 | 1,460 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMeterRegistry.kt` | 현재 파이프라인 코드 | 4,857 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt` | 현재 파이프라인 코드 | 1,469 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerReaderMetrics.kt` | 현재 파이프라인 코드 | 1,190 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt` | 현재 파이프라인 코드 | 1,570 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDataReader.kt` | 현재 파이프라인 코드 | 1,094 | 32 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentTransformer.kt` | 현재 파이프라인 코드 | 1,561 | 48 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentWriter.kt` | 현재 파이프라인 코드 | 824 | 24 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkProcessResult.kt` | 현재 파이프라인 코드 | 152 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkProcessor.kt` | 현재 파이프라인 코드 | 179 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt` | 현재 파이프라인 코드 | 1,014 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingProperties.kt` | 현재 파이프라인 코드 | 354 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt` | 현재 파이프라인 코드 | 3,166 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/redis/OcidMappingRedisWriter.kt` | 현재 파이프라인 코드 | 1,092 | 33 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt` | 현재 파이프라인 코드 | 3,771 | 83 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt` | 현재 파이프라인 코드 | 8,694 | 251 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkWriteConstants.kt` | 현재 파이프라인 코드 | 521 | 14 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt` | 현재 코드 | 3,907 | 80 | 수동 심층 검토+교차검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/JdbcChunkedBatchExecutor.kt` | 현재 파이프라인 코드 | 1,730 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingMergePolicy.kt` | 현재 파이프라인 코드 | 1,397 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` | 현재 파이프라인 코드 | 2,499 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt` | 현재 파이프라인 코드 | 1,460 | 42 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/BasicChunkIngestionService.kt` | 현재 파이프라인 코드 | 5,096 | 125 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/service/OcidLookupService.kt` | 현재 파이프라인 코드 | 1,823 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStateMachine.kt` | 현재 코드 | 4,138 | 87 | 수동 심층 검토+교차검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt` | 현재 파이프라인 코드 | 4,423 | 91 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/state/FailureDecision.kt` | 현재 파이프라인 코드 | 644 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/DefaultChunkFileReader.kt` | 현재 파이프라인 코드 | 11,279 | 251 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/resources/application-local.yml` | 현재 파이프라인 코드 | 713 | 22 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/resources/application-prod.yml` | 현재 파이프라인 코드 | 259 | 15 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/resources/application.yml` | 현재 파이프라인 코드 | 3,292 | 104 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/main/resources/logback-spring.xml` | 현재 파이프라인 코드 | 2,658 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestratorTest.kt` | 테스트 | 7,353 | 185 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/builder/EquipmentDocumentBuilderTest.kt` | 테스트 | 1,872 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerMappingTest.kt` | 테스트 | 4,746 | 115 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt` | 테스트 | 12,256 | 284 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/event/ResultChunkEventPathBuilderTest.kt` | 테스트 | 546 | 18 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/ChunkExecutionMetricsTest.kt` | 테스트 | 3,610 | 84 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/DocumentVolumeMetricsTest.kt` | 테스트 | 3,981 | 86 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparerTest.kt` | 테스트 | 3,011 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt` | 테스트 | 2,708 | 77 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt` | 테스트 | 14,567 | 368 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/resolver/OcidUserIgnResolverTest.kt` | 테스트 | 1,822 | 53 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/BasicChunkIngestionServiceTest.kt` | 테스트 | 2,850 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/service/OcidLookupServiceTest.kt` | 테스트 | 4,867 | 121 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/state/ChunkExecutionStatusTest.kt` | 테스트 | 4,668 | 113 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/DefaultChunkFileReaderTest.kt` | 테스트 | 4,103 | 94 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/test/SynchronizerBlockingPrimitiveGateTest.kt` | 테스트 | 4,490 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/build.gradle` | 빌드·배포·설정 | 1,954 | 61 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/error/GlobalExceptionHandler.kt` | 기타 애플리케이션 코드 | 14,653 | 340 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/config/CorsProperties.kt` | 기타 애플리케이션 코드 | 1,532 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/config/OpenApiConfig.kt` | 기타 애플리케이션 코드 | 1,919 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/config/WebConfig.kt` | 기타 애플리케이션 코드 | 1,012 | 30 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/AdminController.kt` | 기타 애플리케이션 코드 | 4,035 | 108 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/AlertTestController.kt` | 기타 애플리케이션 코드 | 1,429 | 37 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/AuthController.kt` | 기타 애플리케이션 코드 | 4,741 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/BulkLoadController.kt` | 기타 애플리케이션 코드 | 2,655 | 82 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/DonationController.kt` | 기타 애플리케이션 코드 | 4,101 | 97 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/v1/GameCharacterControllerV1.kt` | 기타 애플리케이션 코드 | 1,748 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/v4/GameCharacterControllerV4.kt` | 기타 애플리케이션 코드 | 8,984 | 200 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt` | 기타 애플리케이션 코드 | 6,429 | 150 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/controller/v5/TaskStatusController.kt` | 기타 애플리케이션 코드 | 2,250 | 60 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/LoginRequest.kt` | 기타 애플리케이션 코드 | 1,404 | 44 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/LoginResponse.kt` | 기타 애플리케이션 코드 | 2,286 | 73 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/RefreshRequest.kt` | 기타 애플리케이션 코드 | 495 | 17 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/TokenResponse.kt` | 기타 애플리케이션 코드 | 1,504 | 45 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/admin/AddAdminRequest.kt` | 기타 애플리케이션 코드 | 1,858 | 54 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/donation/SendCoffeeRequest.kt` | 기타 애플리케이션 코드 | 980 | 29 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/donation/SendCoffeeResponse.kt` | 기타 애플리케이션 코드 | 430 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/page/CursorPageRequest.kt` | 기타 애플리케이션 코드 | 775 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/page/CursorPageResponse.kt` | 기타 애플리케이션 코드 | 2,316 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/response/CharacterResponse.kt` | 기타 애플리케이션 코드 | 1,313 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/v4/LikeToggleResponse.kt` | 기타 애플리케이션 코드 | 497 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/dto/v5/EquipmentExpectationResponseV5.kt` | 기타 애플리케이션 코드 | 4,166 | 129 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/filter/MDCFilter.kt` | 기타 애플리케이션 코드 | 2,956 | 80 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/mapper/CharacterViewMapper.kt` | 기타 애플리케이션 코드 | 4,053 | 99 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/security/cors/CorsOriginConstraintValidator.kt` | 기타 애플리케이션 코드 | 1,508 | 43 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/security/cors/CorsOriginValidator.kt` | 기타 애플리케이션 코드 | 6,970 | 189 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/security/cors/ValidCorsOrigin.kt` | 기타 애플리케이션 코드 | 955 | 31 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/IgnValidator.kt` | 기타 애플리케이션 코드 | 1,653 | 52 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/OcidValidator.kt` | 기타 애플리케이션 코드 | 1,470 | 50 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/StarRangeValidator.kt` | 기타 애플리케이션 코드 | 1,864 | 56 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/ValidIgn.kt` | 기타 애플리케이션 코드 | 1,006 | 28 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/ValidOcid.kt` | 기타 애플리케이션 코드 | 853 | 26 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/main/kotlin/maple/expectation/web/validation/ValidStarRange.kt` | 기타 애플리케이션 코드 | 870 | 25 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/java/maple/expectation/arch/ModuleDependencyTest.java` | 테스트 | 9,413 | 276 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/test/ControllerContractTestTemplate.kt` | 테스트 | 6,051 | 210 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/test/ControllerContractTestTemplateExample.kt` | 테스트 | 4,247 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/test/DatabaseCleaner.kt` | 테스트 | 2,372 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/test/README.md` | 테스트 | 4,587 | 153 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5Test.kt` | 테스트 | 8,621 | 242 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `module-web/src/test/kotlin/maple/expectation/web/dto/v4/EquipmentExpectationResponseV4BuilderTest.kt` | 테스트 | 3,720 | 95 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `monitor_loadtest.sh` | 설정·기타 | 6,750 | 149 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `package-lock.json` | 설정·기타 | 17,812 | 513 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `package.json` | 설정·기타 | 130 | 9 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/.gitignore` | 설정·기타 | 480 | 41 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/AGENTS.md` | 설정·기타 | 327 | 5 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/CLAUDE.md` | 설정·기타 | 11 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/README.md` | 설정·기타 | 1,450 | 36 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/app/api/v5/characters/[userIgn]/expectation/route.ts` | 설정·기타 | 3,158 | 101 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/app/globals.css` | 설정·기타 | 697 | 49 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/app/layout.tsx` | 설정·기타 | 305 | 16 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/app/lib/db.ts` | 설정·기타 | 2,210 | 78 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/app/lib/decompress.ts` | 설정·기타 | 1,636 | 38 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/next.config.ts` | 설정·기타 | 133 | 7 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/package-lock.json` | 설정·기타 | 38,153 | 1133 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/package.json` | 설정·기타 | 446 | 23 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/public/file.svg` | 설정·기타 | 391 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/public/globe.svg` | 설정·기타 | 1,035 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/public/next.svg` | 설정·기타 | 1,375 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/public/vercel.svg` | 설정·기타 | 128 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/public/window.svg` | 설정·기타 | 385 | 1 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `query-server/tsconfig.json` | 설정·기타 | 666 | 34 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/airflow-ensure-connections.sh` | 설정·기타 | 2,632 | 68 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/analyze_slow_tasks_with_source.py` | 설정·기타 | 15,417 | 370 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/check-before-commit.sh` | 설정·기타 | 2,798 | 72 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/dev-bootstrap.sh` | 설정·기타 | 2,435 | 63 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/e2e-like-toggle.sh` | 설정·기타 | 10,821 | 325 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/guardrail-test.sh` | 설정·기타 | 18,342 | 385 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/install-systemd-units.sh` | 설정·기타 | 3,118 | 98 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/lib/chaos-minio.sh` | 설정·기타 | 2,271 | 69 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/lib/minio-checks.sh` | 설정·기타 | 2,241 | 71 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/lib/module-health.sh` | 설정·기타 | 2,113 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/rotate-logs.sh` | 설정·기타 | 839 | 35 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/run-pipeline-tests.sh` | 설정·기타 | 2,971 | 67 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/systemd/maple-calculator.service` | 설정·기타 | 717 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/systemd/maple-cleanup.service` | 설정·기타 | 723 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/systemd/maple-external-api.service` | 설정·기타 | 724 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/systemd/maple-synchronizer.service` | 설정·기타 | 729 | 27 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `scripts/validate-minio-vs3.sh` | 설정·기타 | 7,517 | 203 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `settings.gradle` | 현재 시스템 | 1,006 | 36 | 수동 심층 검토+교차검증 |
| `skills-lock.json` | 설정·기타 | 5,349 | 135 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `supabase/.gitignore` | 설정·기타 | 72 | 8 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `supabase/config.toml` | 빌드·배포·설정 | 14,926 | 406 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/CAPTURE_CHECKLIST.md` | 설정·기타 | 2,909 | 103 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/actuator_baseline.json` | 설정·기타 | 0 | 0 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/characters.csv` | 설정·기타 | 75 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/nightmare-fixed-test-run.log` | 설정·기타 | 37,864 | 430 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/nightmare-full-test-run.log` | 설정·기타 | 112,556 | 732 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/nightmare-test-run.log` | 설정·기타 | 10,408 | 138 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/test_10_chars.csv` | 설정·기타 | 114 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test-results/test_baseline.log` | 설정·기타 | 987,083 | 9509 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |
| `test_10_chars.csv` | 설정·기타 | 114 | 11 | 전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증 |

#!/usr/bin/env python3
"""Build the reproducible source catalog used by the portfolio audit.

This script indexes repository paths and records coverage. It deliberately does
not treat a file's presence as proof that every claim inside it is true.
"""

from __future__ import annotations

import collections
import datetime
import hashlib
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "docs/Portfolio_Book/output/research/source_inventory.md"

PDFS = {
    "docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf": {
        "sha256": "e67b747879168c5864eef1ea85cde54a658c0ea42f58d08e5ef752b706a59a7b",
        "pages": 31,
        "size": "A4",
        "role": "작성 가이드(템플릿 아님)",
        "finding": "15~20쪽의 이력서 압축/포트폴리오 4~5개 사례 확장 원칙을 편집 기준으로 사용",
    },
    "docs/Portfolio_Book/이력서.pdf": {
        "sha256": "050ebd6dc8d02e1969d9829d0d93055075fe662d75819d95802a1074b543db2e",
        "pages": 2,
        "size": "A3 계열 세로",
        "role": "미완성 이력서 원본",
        "finding": "개요와 첫 프로젝트 성과란 등에 명시적 공란/플레이스홀더가 존재",
    },
    "docs/Portfolio_Book/포트폴리오.pdf": {
        "sha256": "fb2104e6e9167c9162b865c25ca9a9afbe238250ec4af252f91659729aadba7b",
        "pages": 1,
        "size": "A3 계열 세로",
        "role": "표지만 있는 포트폴리오 원본",
        "finding": "본문 페이지가 없어 검증 소스로 재작성 필요",
    },
}

PRIMARY = {
    "README.md": ("현재 시스템", "현재 모듈·데이터 흐름·기술 스택의 출발점"),
    "gradle/libs.versions.toml": ("현재 시스템", "Spring Boot/Kotlin 등 버전 확인"),
    "settings.gradle": ("현재 시스템", "현재 멀티모듈 경계 확인"),
    "docs/architecture.md": ("설계", "현재/과거 구조를 코드와 교차검증"),
    "docs/engineering-archive-kafka-pipeline.md": ("설계·측정", "Kafka 전환 서술은 커밋·코드·세부 보고서와 교차검증"),
    "docs/endurance-test/endurance-report-82h.md": ("측정", "2026-05-23~27 장기 실행의 환경·부하·오류·자원 수치"),
    "docs/endurance-test/endurance-report-71h.md": ("측정", "2026-06-23~26 장기 실행과 이중 오케스트레이션 결함"),
    "docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md": ("측정", "2026-06-29~07-02 처리율 상한과 단일 writer 병목"),
    "docs/06_Performance_Journey/09_postgresql_notify.md": ("과거 측정", "7,347 RPS 수치의 오류 포함/수정 후 기록 충돌 확인"),
    "docs/06_Performance_Journey/10_real_data_challenge.md": ("과거 측정", "2026-03-24 wrk 조건과 결과; 추정치는 배제"),
    "docs/06_Performance_Journey/README.md": ("과거 측정", "성능 여정의 시계열·조건 분리"),
    "docs/18_Portfolio/external-api-pipeline-evolution.md": ("2차 서술", "현재 코드/커밋으로 다시 검증할 후보 발굴"),
    "docs/18_Portfolio/performance-optimization-portfolio-v2.md": ("2차 서술", "97→7,347을 동일 조건 배수로 표현하지 않도록 충돌 검출"),
    "docs/18_Portfolio/required_portfolio.md": ("제외", "프로젝트 고유 증거가 아닌 범용/과정 문서"),
    "docs/01_ADR/ADR-729-ext-api-item-equipment-loop-throughput.md": ("ADR", "Proposed, 관측 결과 TBD—달성 수치로 사용 금지"),
    "docs/01_ADR/ADR-730_calculator-writer-temp-file-upload.md": ("ADR·측정", "pipe race와 temp-file 교체, 2026-06-22 MinIO E2E 정합성 회복"),
    "docs/01_ADR/ADR-736_disable-legacy-daily-cron.md": ("ADR", "이중 오케스트레이션 원인·수정 근거"),
    "docs/01_ADR/ADR-737_nohup-to-docker-deployment.md": ("ADR", "측정 환경 변화와 배포 결정"),
    "docs/01_ADR/ADR-740_retire-daily-full-pipeline.md": ("ADR", "운영 제어면 변화"),
    "docs/01_ADR/ADR-742_loop-upstream-defer.md": ("ADR", "후속 처리량 선택지와 유보 결정"),
    "docs/01_ADR/ADR-743-small-file-resolution.md": ("ADR", "Proposed—구현 완료로 표현 금지"),
    "docs/01_ADR/ADR-744_internal-network-only-migration.md": ("ADR", "운영 경계와 제한"),
    "module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt": ("현재 코드", "bounded queue·단일 writer·사전 직렬화·비동기 업로드"),
    "module-external-api/src/main/resources/application.yml": ("현재 설정", "rate/in-flight/queue/chunk/Kafka ACK 조건"),
    "module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt": ("현재 코드", "결정적 결과 키·존재 시 재발행·재시도"),
    "module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/KafkaDeliveryAdapter.kt": ("현재 코드", "handler outcome을 commit/retry/DLT delivery action으로 변환"),
    "module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/adapter/PartitionLane.kt": ("현재 코드", "성공한 delivery action 뒤 partition 순서대로 수동 ACK"),
    "module-pipeline-messaging/src/main/kotlin/maple/pipeline/messaging/config/PipelineKafkaConsumerConfiguration.kt": ("현재 설정", "Kafka MANUAL_IMMEDIATE ACK mode"),
    "module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt": ("현재 코드", "스트리밍 처리"),
    "module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt": ("현재 코드", "artifact writer session을 통한 결과 직렬화"),
    "module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/write/ArtifactWriter.kt": ("현재 코드", "gzip 임시 파일 session 생성"),
    "module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/write/GzipArtifactSession.kt": ("현재 코드", "stream close 뒤 비동기 업로드와 임시 파일 정리"),
    "module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt": ("현재 코드", "result-ready 투영 흐름"),
    "module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt": ("현재 코드", "read model write/upsert 경계"),
    "module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt": ("현재 코드", "DB claim/lease/state와 ACK 순서"),
    "module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStateMachine.kt": ("현재 코드", "retry/terminal 상태 결정"),
    "module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt": ("현재 코드", "Redis multi-get 선조회·miss의 PostgreSQL batch read 경계"),
    "module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt": ("현재 코드", "Redis read-model cache multi-get/multi-put 구현"),
    "module-infra/src/main/resources/db/migration/V128__chunk_execution.sql": ("현재 스키마", "chunk identity·lease·retry 상태 영속화"),
    "module-cleanup/src/main/kotlin/maple/cleanup/inbox/ConsumedChunkInbox.kt": ("현재 코드", "소비 완료 inbox/idempotency 경계"),
    "module-pipeline-artifact/src/main/kotlin/maple/pipeline/artifact/inbox/ObjectStorageCleanupInboxStore.kt": ("현재 코드", "object storage 기반 durable cleanup inbox와 replay/integrity 판정"),
}


def tracked_files() -> list[str]:
    raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    return sorted(item for item in raw.decode("utf-8").split("\0") if item)


def line_count(path: pathlib.Path) -> str:
    try:
        data = path.read_bytes()
    except (OSError, PermissionError):
        return "-"
    if b"\0" in data[:8192]:
        return "binary"
    return str(data.count(b"\n") + (1 if data and not data.endswith(b"\n") else 0))


def category(path: str) -> str:
    p = pathlib.PurePosixPath(path)
    lower = path.lower()
    if path in PRIMARY:
        return PRIMARY[path][0]
    if path.startswith("docs/01_ADR/") or path.startswith("docs/adr/"):
        return "ADR"
    if path.startswith("docs/"):
        if any(token in lower for token in ("performance", "benchmark", "load_test", "load-test", "endurance")):
            return "성능·부하 문서"
        return "저장소 문서"
    if "/src/test/" in path or p.name.endswith(("Test.kt", "Test.java")):
        return "테스트"
    if path.startswith(("module-external-api/", "module-calculator/", "module-synchronizer/", "module-cleanup/", "module-rest-controller/")) and "/src/main/" in path:
        return "현재 파이프라인 코드"
    if "/src/main/" in path and p.suffix.lower() in {".kt", ".java", ".py", ".sql"}:
        return "기타 애플리케이션 코드"
    if p.suffix.lower() in {".gradle", ".kts", ".yml", ".yaml", ".toml", ".properties"}:
        return "빌드·배포·설정"
    if any(token in lower for token in ("benchmark", "load-test", "load_test", "performance", "endurance")):
        return "성능·부하 도구"
    return "설정·기타"


def included(path: str) -> bool:
    # The request is an exhaustive repository audit. Keep every tracked path in
    # the catalog; category labels make the large appendix navigable.
    return not path.startswith("docs/Portfolio_Book/output/")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def esc(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def main() -> None:
    files = tracked_files()
    catalog = [path for path in files if included(path) and not path.startswith("docs/Portfolio_Book/output/")]
    category_counts = collections.Counter(category(path) for path in catalog)
    docs = [path for path in files if path.startswith("docs/")]
    tests = [
        path
        for path in files
        if "/src/test/" in path
        or "/test/" in path
        or pathlib.PurePosixPath(path).name.lower().startswith("test_")
        or pathlib.PurePosixPath(path).name.endswith(("Test.kt", "Test.java"))
    ]
    pipeline = [
        path
        for path in files
        if path.startswith(("module-external-api/", "module-calculator/", "module-synchronizer/", "module-cleanup/", "module-rest-controller/"))
        and "/src/main/" in path
    ]
    adrs = [path for path in files if path.startswith("docs/01_ADR/") or path.startswith("docs/adr/")]
    perf = [
        path
        for path in files
        if any(token in path.lower() for token in ("benchmark", "load-test", "load_test", "performance", "endurance"))
    ]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    captured_at = datetime.datetime.now(datetime.timezone.utc).astimezone().isoformat(timespec="seconds")
    reachable_commits = int(
        subprocess.check_output(
            ["git", "rev-list", "--all", "--count"], cwd=ROOT, text=True
        ).strip()
    )

    pdf_rows = []
    for rel, info in PDFS.items():
        actual = sha256(ROOT / rel)
        status = "일치" if actual == info["sha256"] else f"불일치({actual})"
        pdf_rows.append(
            f"| `{esc(rel)}` | {info['pages']} | {info['size']} | {info['role']} | 0/없음 | `{info['sha256']}` ({status}) | {info['finding']} |"
        )

    primary_rows = []
    for path, (kind, purpose) in PRIMARY.items():
        exists = "확인" if (ROOT / path).is_file() else "누락"
        primary_rows.append(f"| `{esc(path)}` | {kind} | {exists} | {purpose} |")

    count_rows = [f"| {esc(name)} | {count:,} |" for name, count in sorted(category_counts.items())]
    appendix_rows = []
    for path in catalog:
        absolute = ROOT / path
        inspection = "수동 심층 검토+교차검증" if path in PRIMARY else "전수 경로/텍스트 기계 색인; 주장 채택 시 원문 재검증"
        appendix_rows.append(
            f"| `{esc(path)}` | {category(path)} | {absolute.stat().st_size:,} | {line_count(absolute)} | {inspection} |"
        )

    draft_rows = []
    for path in files:
        if path.startswith("docs/Portfolio_Book/") and path.endswith(".md") and path != "docs/Portfolio_Book/README.md":
            draft_rows.append(f"| `{esc(path)}` | 이전 작성 초안 | 현재 코드·원시 측정과 충돌 가능; 최종 문구의 단독 근거로 사용하지 않음 |")

    text = f"""# 소스 인벤토리

## 조사 기준

- 기준 저장소/HEAD: `zbnerd/probabilistic-valuation-engine` / `{head}`
- 카탈로그 재생성 시각: `{captured_at}`
- 범위: 모든 Git ref에서 도달 가능한 커밋, GitHub PR/이슈, 원본 PDF 3개, 모든 Git 추적 파일(문서·코드·테스트·설정 포함), `docs/ai_traces` 및 실제 발견 경로 `docs/ai-traces`.
- 이 문서는 **경로를 발견했다는 사실과 주장이 입증됐다는 사실을 구분**한다. 전체 후보는 기계 색인했고, 최종 문구에 채택한 증거는 원문·코드·Git/GitHub를 수동 교차검증했다.
- 신뢰 우선순위: 현재 코드/실제 diff/원시 측정(T1) → 조건이 기재된 세부 보고서(T2) → ADR·설계 문서(T3) → PR/이슈/AI 세션/기존 포트폴리오의 서술(T4). T3/T4만으로 완료나 수치를 단정하지 않았다.

## 원본 PDF 전수 확인

2026-08-08 completion audit에서 세 입력 PDF를 PyMuPDF와 pypdf로 구조·텍스트·페이지를 확인하고, 2배율 래스터로 **모든 페이지(31+2+1)를 시각 검사**했다. 암호화·AcroForm 입력 필드는 모두 없었다. 이 스크립트는 그 판정 자체를 재현한다고 주장하지 않고 원본 SHA-256만 매 실행 검증한다. 따라서 원본 위에 폼 값을 넣는 방식이 아니라, 원본은 보존하고 별도 완성본을 생성한다.

| 파일 | 페이지 | 판형 | 분류 | 폼/암호화 | SHA-256 | 판정 |
|---|---:|---|---|---|---|---|
{chr(10).join(pdf_rows)}

조사 시작 시점의 입력 경로에는 위 세 원본 외 PDF가 없었고 이전 버전·중복 입력 문서는 0개였다. `output/final/`의 두 PDF는 이 조사로 생성된 산출물이므로 입력 분류에서 제외한다. 기존 Markdown 초안은 아래에서 별도 2차 자료로 분류한다.

## 조사 산출물의 역할

| 산출물 | 완전성/용도 |
|---|---|
| `commit_inventory.csv` | 이 카탈로그 재생성 시점 `git rev-list --all`의 고유 {reachable_commits:,} commits와 대조한 전 커밋 목록; 실제 diff/numstat/name-status 기반 요약·분류 |
| `pr_inventory.md` + `pr_detail_inventory.jsonl` | GitHub REST pagination·GraphQL/Search 교차검증; 710개 PR의 상태와 누락 209개+#1464의 complete commit/file/discussion/formal-link 연결 기록 |
| `issue_inventory.md` | GitHub REST pagination·Search 교차검증, 상태/본문/댓글/PR 연결과 포트폴리오 관련성 기록 |
| `ai_traces_summary.md` | AI 기록을 명령이 아닌 비신뢰 데이터로 읽고, 코드·Git으로 재검증 가능한 후보만 추출 |
| `evidence_ledger.md` | 최종 이력서·포트폴리오의 핵심 주장별 근거, 조건, 개인 기여, 한계를 연결 |

### 경로 불일치

요청에 적힌 `docs/ai_traces`(밑줄)는 존재하지 않는다. 실제로 발견된 무시(ignored) 경로는 `docs/ai-traces`(하이픈)이며, 그 경로까지 재귀 전수 색인 대상으로 포함했다. 추적 파일인 `docs/ai-traces/.gitignore`와 무시된 세션 자료를 구분한다.

- 실제 corpus: 2026-06-09~2026-07-06, 166 session directories, 882 files, 5,977,278 logical bytes.
- 358개 gzip은 전부 integrity pass. JSONL/JSONL.GZ stream 454개 중 453개는 전체 parse했다. `20260619/20260619-172927-4111484/tool-use.jsonl.gz` 1개는 gzip은 valid지만 line 818~819부터 두 JSON object가 중첩·중복되고 828~829의 status field도 충돌해 partial로 표시했다.
- trace 안의 command/tool input/completion claim은 실행하지 않았고, credential·private prompt/tool payload는 재현하지 않았다.

### ref·증거 시점 구분

- 이 상세 **파일 카탈로그**의 기준은 작업트리 HEAD `{head}`이며, 커밋 인벤토리는 재생성 시점의 모든 로컬·원격 추적·태그 ref를 별도로 합집합한다.
- 현재 checkout에는 merged PR #1463의 final source tip `1f47173e3`가 포함돼 있다. 현재 cleanup은 object-storage-backed durable inbox를 사용한다. 다만 이를 Kafka·artifact·DB 전 구간의 exactly-once 보장으로 확대하지 않는다.
- 모든 ref의 commit/diff는 `commit_inventory.csv`에 포함했다. #1463의 ADR-745~749와 다섯 evidence report는 현재 source와 함께 별도 심층 검토했다.
- #1463의 86 focused tests·4 worker bootJar evidence는 tip 직전 `11ee3c727` 기준이다. final `1f47173e3`에서 production subscription class 4개·test file 2개가 변경됐으며, 이 마지막 수정을 포함한 rerun은 발견하지 못했다.

## 심층 검토한 1차·핵심 2차 자료

| 경로 | 분류 | 상태 | 사용/배제 기준 |
|---|---|---|---|
{chr(10).join(primary_rows)}

## 기존 Portfolio_Book 마크다운 분류

기존 장문 초안은 탐색용 2차 자료다. 시점이 다른 V1~V5, 제안 상태 ADR, 측정 조건이 다른 수치를 한 서술에 합친 부분이 있어 최종 결과의 단독 근거로 쓰지 않았다.

| 경로 | 분류 | 처리 |
|---|---|---|
{chr(10).join(draft_rows)}

## 전수 색인 집계

- Git 추적 파일 전체: {len(files):,}개
- `docs/` 추적 파일: {len(docs):,}개
- ADR 후보: {len(adrs):,}개
- 테스트 후보: {len(tests):,}개
- 현재 external-api/calculator/synchronizer/cleanup/rest-controller main 소스: {len(pipeline):,}개
- 성능·부하·장기 실행 경로 후보: {len(perf):,}개
- 아래 중복 제거 상세 카탈로그: {len(catalog):,}개(이 조사 산출물 자체는 제외)

| 분류 | 파일 수 |
|---|---:|
{chr(10).join(count_rows)}

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
{chr(10).join(appendix_rows)}
"""
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({len(text.splitlines()):,} lines)")


if __name__ == "__main__":
    main()

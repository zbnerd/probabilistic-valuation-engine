# 전수 증거 기반 이력서·포트폴리오 재구축 설계

- Status: Approved
- Date: 2026-08-01
- Owner: 이승준
- Working branch: `docs/exhaustive-portfolio-rebuild`

## 1. 배경

현재 `docs/Portfolio_Book/output/final/`의 이력서와 포트폴리오는 저장소를 폭넓게 조사했지만 다음 문제가 있다.

- `docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf`가 제시하는 작성 구조보다 증거 감사와 한계 설명이 앞선다.
- 이력서의 문제 해결 문장과 포트폴리오 사례 제목이 일치하지 않는다.
- 일부 대표 사례만 최종 문서에 들어가고, 나머지 커밋·diff·PR·이슈·AI trace는 별도 인벤토리에만 남았다.
- 프로젝트 문서가 사실로 기록한 RPS와 성과를 별도 재계산이나 원시 출력 부재를 이유로 축소하거나 기각했다.
- 모든 문제 해결 사례에 동일한 그림·문제·해결·결과 구조가 적용되지 않았다.

사용자는 분량과 사례 수에 제한을 두지 않고 다음 자료를 모두 참조하고 모두 기록하도록 확정했다.

1. `docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf`
2. 모든 Git 커밋과 각 커밋의 모든 parent별 diff
3. 모든 GitHub PR 기록
4. 모든 GitHub 이슈 기록
5. 실제 저장소 경로 `docs/ai-traces/`의 모든 세션과 파일
6. 저장소 문서에 기록된 모든 성과와 문제 해결

## 2. 목표

### 2.1 전수성

- 감사 스냅샷에 존재하는 모든 원본 항목을 개별 기록한다.
- 모든 원본 항목은 문제 해결 사례 또는 `record-only` 항목에 연결한다.
- 대표 사례 선별, 개수 제한, 페이지 제한을 적용하지 않는다.
- 같은 변경이 이슈, PR, 커밋, AI trace에 걸쳐 있어도 각 원본 레코드는 개별 보존한다.

### 2.2 가이드 구조 준수

- 이력서의 모든 사례를 `문제 + 해결 + 결과 + 도메인`의 한 문장으로 표현한다.
- 포트폴리오는 이력서 문장을 글자 단위로 동일한 제목으로 사용한다.
- 모든 포트폴리오 사례에 `제목 → Mermaid 그림 → 문제 → 해결 → 결과 → 근거` 구조를 반복한다.

### 2.3 사실성

- 프로젝트 문서가 실측으로 기록한 수치와 성과는 사실로 사용한다.
- 문서가 `목표`, `예상`, `추정`, `실패`, `롤백`, `미검증`으로 표시한 상태는 그대로 보존한다.
- 문서에 없는 수치, 원인, 인과관계, 개선률을 계산하거나 추측하지 않는다.

### 2.4 추적 가능성

- 이력서와 포트폴리오의 모든 문장에서 원본 증거까지 추적할 수 있어야 한다.
- 기계 판독 가능한 장부와 사람이 읽을 수 있는 장부를 함께 제공한다.
- 감사 스냅샷과 출판 커밋의 경계를 명시한다.

## 3. 비목표

- 새로운 성능 측정이나 부하 테스트를 실행하지 않는다.
- 데이터베이스, 서버, Kafka, MinIO 또는 외부 서비스를 변경하지 않는다.
- GitHub PR·이슈의 상태나 내용을 변경하지 않는다.
- 문서에 없는 성과를 코드만 보고 정량 성과로 승격하지 않는다.
- 원본 가이드의 저작물 문장이나 시각 템플릿을 복제하지 않는다. 작성 원칙과 정보 구조만 적용한다.

## 4. 감사 스냅샷 경계

전수성은 무한히 움직이는 저장소가 아니라 명시적인 스냅샷을 기준으로 검증한다.

- Git cutoff: 수집 시작 직전의 정확한 HEAD SHA와 전체 ref→object SHA manifest
- GitHub cutoff: 열거 시작·종료·최종 reconciliation UTC 시각
- AI trace cutoff: 수집 시작 시점의 `docs/ai-traces/` 파일 목록과 각 SHA-256
- Document cutoff: Git cutoff에 포함된 tracked 문서

산출물을 추가하는 출판 커밋은 자기 자신의 hash를 자기 tree 안에 기록할 수 없다. 따라서 출판 커밋은 다음 스냅샷 범위에 포함한다. 현재 산출물에는 `source snapshot SHA`와 `publication commit is outside this snapshot`을 명시한다. 이는 누락이 아니라 재현 가능한 감사 경계다.

## 5. 전체 데이터 흐름

```text
Git / GitHub / docs / AI traces
              │
              ▼
      raw source snapshots
              │
              ▼
     normalized source ledger
              │
      ┌───────┴────────┐
      ▼                ▼
problem/achievement  record-only
case catalog         source records
      │                │
      └───────┬────────┘
              ▼
      exhaustive evidence book
              │
      ┌───────┴────────┐
      ▼                ▼
 exhaustive resume  exhaustive portfolio
 one-line cases     expanded cases + Mermaid
```

## 6. 원본 수집 설계

### 6.1 Git

`git for-each-ref`가 반환하는 `refs/*` 전체와 HEAD를 ref manifest에 기록하고, 이 manifest에서 reachable한 unique commit을 열거한다. branches, remote-tracking refs, tags, pull refs, stash, notes 등 namespace를 임의로 제외하지 않는다. symbolic ref는 resolved object SHA와 symbolic target을 함께 기록한다. 로컬에 존재하지 않는 GitHub PR commit은 PR API의 commit 목록에서 별도 source record로 보존한다.

- root commit: empty tree와 diff
- 일반 commit: 각 parent와 diff
- merge commit: first parent만이 아니라 모든 parent와 각각 diff
- 각 diff: parent SHA, child SHA, file status, rename score, numstat, patch, binary 여부
- commit metadata: author/committer, authored/committed time, parents, subject, body, signature 상태

전체 patch는 parent별 파일로 만든 뒤 분할 압축 archive에 저장한다. 각 archive 항목은 manifest의 stable ID와 SHA-256으로 검증한다. GitHub 단일 파일 제한을 넘지 않도록 archive는 고정 크기 이하 volume으로 분할한다.

Stable ID 예시:

- `GIT-<full-sha>-ROOT`
- `GIT-<full-sha>-P01`
- `GIT-<full-sha>-P02`

### 6.2 GitHub PR

모든 PR 번호를 먼저 전수 열거한 뒤 각 번호를 개별 수집한다. 열거 시작·종료 시각과 각 항목의 `fetched_at`을 기록한다. 전체 상세 수집이 끝나면 PR set과 각 `updated_at`을 다시 열거하고, 새 번호 또는 수집 뒤 갱신된 번호를 재수집한다. 변경 없는 reconciliation pass가 완료된 시각을 GitHub cutoff로 사용한다.

- 번호, 제목, 본문, 작성자, label, state, draft, 생성·수정·종료·merge 시각
- base/head ref와 SHA, merge commit
- commits, changed files, patch metadata
- reviews, review comments, conversation comments
- requested reviewers, assignees, milestones
- closing issue relations와 timeline events

일괄 API가 일부 레코드를 반환하지 못하면 해당 번호를 개별 REST/GraphQL 요청으로 재시도한다. API가 삭제·권한·보존 한계로 데이터를 제공하지 않음을 최종 응답으로 명시하면 레코드 자체를 `confirmed-unavailable`로 완료 처리하고 HTTP 상태, endpoint, 재시도 횟수, 확인 시각을 기록한다. transient 오류가 계속되거나 enumeration set 자체의 완전성을 확인할 수 없으면 blocker다. 접근 불가 내용을 추측하지 않는다.

### 6.3 GitHub 이슈

모든 이슈 번호를 전수 열거하고 PR과 구분한다. PR과 동일하게 열거 시작·종료 시각, 항목별 `fetched_at`, `updated_at` reconciliation pass를 적용한다.

- 번호, 제목, 본문, 작성자, label, state, state reason
- 생성·수정·종료 시각
- comments, assignees, milestone, reactions, timeline
- 연결 PR, closing relation, 명시적으로 언급된 commit

closed 상태만으로 구현 완료를 추측하지 않는다. 원문이 완료·보류·중단·중복·미구현으로 기록한 상태를 그대로 사용한다.

### 6.4 AI traces

사용자가 지칭한 `docs/ai_traces`의 실제 저장소 경로는 `docs/ai-traces/`다. 해당 경로의 모든 파일을 개별 처리한다.

- session directory와 artifact path
- 파일 크기, SHA-256, compression, parse 상태
- prompts, session events, tool uses, summary, git log, git diff의 레코드 수
- 파일 내 각 JSON/JSONL 레코드의 위치와 parse 결과
- malformed/interleaved record의 byte 또는 line 위치

AI trace에 어떤 제안이나 완료 주장이 존재했다는 사실은 그대로 기록한다. 그 내용이 실제 구현·성과라는 주장은 commit diff, PR, 이슈 또는 프로젝트 문서와 명시적으로 연결될 때만 사례 결과로 사용한다.

### 6.5 저장소 문서

tracked 문서를 모두 source ledger에 포함하고, 성과·문제·해결·결과·측정 조건을 추출한다.

- 문서의 수치와 상태 label을 그대로 보존한다.
- 같은 값이 다른 날짜·조건에 존재하면 서로 다른 기록으로 유지한다.
- 문서가 명시한 개선률은 그대로 사용할 수 있다.
- 문서에 없는 재계산값으로 원문을 교정하지 않는다.

파일 존재만으로 문서 조사가 끝났다고 판단하지 않는다. 각 Markdown/text 문서를 heading, paragraph, list item, table row, code/diagram block 단위로 나누고, 한 block에 독립 주장이 여러 개면 sentence/claim 단위로 다시 나눈다. 각 단위는 stable document claim ID, path, line span, raw hash, 분류를 갖는다.

```text
DOC-<path-hash>-L<start>-L<end>-C<index>
```

모든 document block은 `problem`, `solution`, `result`, `measurement`, `target`, `expected`, `failure`, `rollback`, `metadata`, `record-only` 중 하나 이상으로 분류한다. 모든 block/claim이 분류됐는지를 coverage manifest에서 검증해 문서 내부 누락을 탐지한다.

### 6.6 사실 근거 우선순위

모든 문서는 source record로 보존하지만 모든 문서가 같은 역할을 갖지는 않는다.

1. 이번 대화에서 사용자가 확정한 사실 처리 규칙
2. 프로젝트의 측정·운영·endurance·performance 문서와 Accepted ADR의 기록
3. 구현 코드, 테스트, commit diff, merged PR
4. 이슈와 계획 문서가 명시한 상태
5. AI trace와 이전 `output/` 생성 산출물

기존 `docs/Portfolio_Book/output/`은 이번 재구축의 교체 대상이며, 프로젝트 문서의 RPS 성과를 기각한 이전 판단을 새 문서의 사실 근거로 재사용하지 않는다. 그렇더라도 해당 파일과 이를 추가한 commit은 전수 장부에서 삭제하지 않고 이전 생성 산출물로 기록한다.

같은 수치가 서로 다른 날짜·환경·실행에 기록되면 충돌로 합치지 않고 모두 별도 사실로 수록한다. 동일 실행을 두 문서가 직접 다르게 기록하고 후속 정정도 없으면 양쪽 원문을 모두 기록하고 `source conflict`로 표시하며 임의로 하나를 선택하지 않는다.

## 7. 정규화 장부

모든 원본은 공통 source record를 갖는다.

```text
source_id
source_type
source_locator
snapshot_id
title
recorded_status
recorded_at
raw_hash
raw_archive_locator
explicit_relations[]
case_ids[]
classification
record_only_reason
availability_status
privacy_redactions[]
parse_status
```

관계는 다음 근거가 있을 때만 생성한다.

- GitHub closing relation
- PR이 반환한 commit SHA
- source 본문에 명시된 full commit/PR/issue reference
- AI trace가 포함한 exact diff hash와 현재 Git object의 동일성
- 문서가 명시한 commit/PR/issue link

시간·주제·파일명이 비슷하다는 이유만으로 관계를 만들지 않는다.

## 8. 사례 분류

### 8.1 문제 해결·성과 사례

다음 중 하나가 원본에 명시되면 사례를 생성한다.

- 문제와 해결이 함께 기록됨
- 변경 전후 결과가 기록됨
- 장애·회귀·실패를 재현하고 수정 또는 롤백함
- 기능·아키텍처·운영 안전장치를 구현하고 구현 결과가 확인됨
- 테스트·장기 실행·부하 측정 결과가 기록됨
- 오픈소스 변경이 upstream에 merge됨

정량 성과가 없어도 구현 또는 merge가 사실이면 정성적 결과로 기록한다. 성능 개선률은 원본에 있을 때만 사용한다.

원본에 성과만 있고 문제 또는 해결이 기록되지 않은 경우도 사례에서 제외하지 않는다. 이력서에는 기록된 성과를 그대로 쓰고 `원문에 문제·해결 과정 미기록`을 덧붙인다. 포트폴리오의 문제·해결 섹션에도 같은 문구를 사용하며 빈칸을 추측으로 채우지 않는다. Mermaid는 source→recorded result의 증거 흐름만 표현한다.

### 8.2 record-only

문제 해결 사례로 표현할 근거가 없는 항목도 삭제하지 않는다.

- merge-only record
- formatting, typo, dependency refresh
- 문서 snapshot
- 계획만 있고 실행되지 않은 항목
- 중복 또는 취소된 이슈
- 접근 불가 metadata record

이들은 증거 장부에 개별 수록하고 명시적으로 `record-only`로 표시한다. 문제·해결·성과를 만들어내지 않는다.

### 8.3 실패와 롤백

실패한 시도와 롤백도 독립적인 문제 해결 사례가 될 수 있다. 결과를 성공으로 바꾸지 않고 다음 상태 중 하나로 기록한다.

- failed experiment
- reverted
- superseded
- diagnosed, no fix applied
- implemented, runtime verification pending
- measured result

## 9. 수치와 사실 처리

### 9.1 필수 복원 성과

다음을 포함하되 이에 한정하지 않는다.

- 2026-01-20~03-30 성능 여정: 97 → 7,347 RPS, 76배
- p99 4,100ms → 36ms, 99% 감소
- 에러율 59.7% → 0%
- 223 → 97 RPS SingleFlight 회귀
- 97 → 555 RPS L1 fast path
- 555 → 674 RPS write-behind
- 674 → 965 RPS 병렬 preset
- stateless 325 RPS trade-off
- auto warmup 287 → 940 RPS
- 빈 DB 10,994 RPS와 실데이터 200K~300K rows 7,347 RPS의 별도 측정
- PGMQ drain 3.3 → 90 tasks/s, 계산 지연 25,466 → 864ms, 503 오류율 98.4% → 0%
- Like 경로 DB QPS 2,500~3,500/s → 200 미만과 Hikari 사용률 75~125% → 10~15%
- 문서에 기록된 82h, 약 71h, 약 80h 실행의 모든 수치와 사건
- MinIO temp-file correction, claim-check, 멱등성, ETL ownership, 보안 사고 대응 등 모든 확인 사례

### 9.2 서로 다른 실행의 분리

- 2026-03-19의 7,347 RPS / p99 36ms / errors 65
- post-fix 빈 DB 측정의 c200 7,347 RPS, c500 10,994 RPS / errors 0
- 2026-03-24 실데이터 측정의 7,347 RPS / p99 36ms / errors 0

각 실행은 날짜와 조건을 붙여 모두 기록한다. 앞선 실행의 오류를 이유로 후속 실행을 기각하지 않는다.

### 9.3 금지되는 재해석

- 문서의 약 71h를 68h27m로 재계산해 대체하지 않는다.
- 문서의 lifetime average를 별도 산술값으로 대체하지 않는다.
- 문서가 확정하지 않은 06-25 중단 원인을 06-26 원인과 같다고 추측하지 않는다.
- `raw stdout 부재`, `문서 기반이라 제한`을 이유로 사용자가 사실로 확정한 문서 성과를 낮추지 않는다.
- scale-out 예상값, target throughput, 후속 동일 조건 after가 없는 변경을 측정 성과로 승격하지 않는다.

## 10. 이력서 설계

### 10.1 상단

- 제목: `이승준 이력서`
- 증명사진: `docs/Portfolio_Book/이력서.pdf` 내부 원본 이미지를 추출해 비율을 유지하여 재사용
- 지원동기: 특정 회사·공고를 만들지 않고 보편적인 백엔드 역할에 기여할 수 있는 사실 기반 2~3줄
- 자기소개: 추상적 성향 대신 가장 강한 측정 성과와 운영·문제 해결 역량
- 프로필: 이름, 이메일, 신입 여부, 경력, 교육, GitHub, 블로그, 포트폴리오 링크

원본 증명사진 PDF는 수정하지 않고 hash를 검증한다.

### 10.2 프로젝트

프로젝트별로 다음 metadata를 먼저 둔다.

- 설명형 프로젝트 제목
- 월 단위 기간
- 버전을 포함한 핵심 기술
- 포지션별 참여 인원
- 한 줄 서비스 개요

그 아래 모든 확인 사례를 한 문장으로 수록한다.

```text
<도메인/기능>에서 <문제>를 <해결 방식>으로 해결하여 <기록된 결과>를 달성 [source IDs]
```

문장 수와 페이지 수를 제한하지 않는다. 기본기, 성능, 정합성, 비동기, 메시징, 데이터 접근, 테스트, 관측성, 운영, 보안, 배포, 오픈소스 사례를 모두 포함한다.

### 10.3 기타 이력

- Open Source Contribution
- Career
- Education
- Certificate

같은 정보를 상단과 하단에 불필요하게 반복하지 않는다. 원본 source ID는 사람이 읽을 수 있는 짧은 footnote 또는 clickable link로 연결한다. 지원동기, 자기소개, 프로젝트 metadata, 경력·교육·자격을 포함해 사실을 말하는 모든 문장에 source ID를 부여한다. 제목·레이아웃 label처럼 사실 주장이 아닌 편집 요소만 source mapping 대상에서 제외한다.

## 11. 포트폴리오 설계

### 11.1 전체 구조

- 표지
- 사실 기반 핵심 성과 index
- 프로젝트별 자동 목차
- 모든 문제 해결·성과 사례
- 모든 record-only 항목의 source index
- coverage manifest

### 11.2 사례 반복 단위

모든 사례는 다음 구조를 예외 없이 사용한다.

1. 이력서와 완전히 동일한 제목
2. Mermaid 그림
3. 문제
4. 해결
5. 결과와 측정 조건
6. 관련 commit과 parent별 diff
7. PR과 issue
8. AI trace와 문서 근거
9. 원문 상태가 요구할 때만 목표·예상·미검증 경계

가독성을 위해 사례당 분량은 내용에 따라 늘릴 수 있으나 섹션을 생략하지 않는다. 결과가 없는 사례는 결과를 만들지 않고 원문 상태를 적는다.

### 11.3 Mermaid 규칙

모든 사례는 `.mmd` source와 렌더링된 SVG를 갖는다.

- 데이터 흐름: `flowchart`
- 호출·ACK·retry 순서: `sequenceDiagram`
- 상태 전이: `stateDiagram-v2`
- 변경 전후: 두 subgraph를 사용한 `flowchart`
- 시간 기반 장애: `timeline` 또는 순서가 명확한 flowchart

아이콘과 앱 화면 캡처를 사용하지 않는다. 사각형과 명시적 텍스트를 사용한다. 원본에 없는 component, 연결, 인과관계를 추가하지 않는다. 구조 정보가 부족한 record는 증거 관계 또는 before/change/recorded-after 흐름만 표현한다.

Mermaid source가 compile되지 않으면 해당 사례와 전체 PDF build를 실패시킨다.

## 12. 전수 증거 장부와 산출물

### 12.1 사람이 읽는 산출물

- `output/final/이력서_완성본.md`
- `output/final/이력서_완성본.pdf`
- `output/final/포트폴리오_완성본.md`
- `output/final/포트폴리오_완성본.pdf`
- `output/final/전수증거장부.md`
- 크기에 따라 분할된 `output/final/전수증거장부-<volume>.pdf`
- `output/final/검토필요사항.md`

`검토필요사항.md`는 문서 사실을 다시 의심하는 용도가 아니다. 외부 API가 실제로 제공하지 않은 필드, 손상된 원본 위치, 개인정보 마스킹, 출판 snapshot 경계만 기록한다.

이력서나 포트폴리오가 renderer 또는 GitHub 파일 크기 한계를 넘으면 사례 ID 순서에 따라 deterministic volume으로 분할한다. 이때 기본 이름의 PDF는 전체 목차와 각 volume의 SHA-256·case 범위·상대 링크를 담은 master index가 되고, 본문은 `이력서_완성본-001.pdf`, `포트폴리오_완성본-001.pdf` 형식으로 이어진다. 모든 volume의 합집합이 전체 사례와 정확히 일치해야 하며 사례 중복은 허용하지 않는다. 단일 파일로 안전하게 생성 가능하면 분할하지 않는다.

### 12.2 기계 판독 산출물

- `output/research/source_records.jsonl`
- `output/research/case_catalog.jsonl`
- `output/research/case_source_map.csv`
- `output/research/commit_inventory.csv`
- `output/research/pr_inventory.jsonl`
- `output/research/issue_inventory.jsonl`
- `output/research/ai_trace_inventory.jsonl`
- `output/research/document_claim_inventory.jsonl`
- `output/research/coverage_manifest.json`
- `output/research/coverage_manifest.md`
- 분할된 `output/research/commit-diffs-<volume>.tar.gz`
- `output/diagrams/<case-id>.mmd`
- `output/diagrams/rendered/<case-id>.svg`

큰 파일은 GitHub 제한과 clone 비용을 고려해 deterministic volume으로 분할한다. 각 volume은 파일 목록, byte count, SHA-256을 manifest에 기록한다.

### 12.3 선택한 구조의 trade-off

선택한 계층형 전수본은 모든 성과·문제 해결을 이력서와 포트폴리오 양쪽에 담되, 전체 raw patch와 원본 record 전문은 증거 장부에 한 번만 저장한다.

- 얻는 것: 사례 중심 가독성, 원본까지의 완전한 추적성, 원시 자료 중복 감소
- 감수하는 것: 문서와 archive의 큰 용량, 긴 생성 시간, 많은 Mermaid render
- 포기하는 것: 2쪽 이력서, 4~5개 포트폴리오 사례, 짧은 단일 PDF

이 trade-off는 사용자의 `개수·분량 제한 없이 전부 기록` 결정을 우선한 결과다.

## 13. 개인정보와 비밀정보

전수 기록은 비밀 유출을 허용하지 않는다.

- 원본 AI trace 파일은 수정하지 않고 path와 hash로 참조한다.
- 생성 장부에는 token, API key, password, private key, session cookie를 복제하지 않는다.
- 비밀이 있는 레코드는 source ID, 위치, hash, redaction type을 기록하고 값은 `[REDACTED]`로 대체한다.
- 개인 이메일은 이력서 소유자의 공개 연락처만 허용한다. 타인의 이메일·연락처는 마스킹한다.
- prompt 전문에 민감정보가 있으면 전문 대신 안전한 구조화 요약과 raw locator를 제공한다.

이 방식은 레코드를 누락하지 않으면서 비밀 값을 재배포하지 않는다.

## 14. 실패 처리와 재개

- 수집기는 checkpoint를 저장하고 번호·SHA·파일 단위로 재개 가능해야 한다.
- GitHub rate limit 또는 일시적 오류는 backoff 후 재시도한다.
- 일괄 조회 누락은 개별 조회 queue로 이동한다.
- gzip/JSONL 오류는 파일 전체를 버리지 않고 raw hash, 오류 위치, 정상 record 수를 남긴다.
- 관계를 만들 수 없는 레코드는 `unlinked`로 남기고 추측해 연결하지 않는다.
- source record 누락, case 제목 불일치, Mermaid compile 실패, PDF overflow는 build 실패다.
- API가 `confirmed-unavailable`로 확정한 field는 해당 상태를 기록하면 수집 완료로 계산한다. transient failure, count 불일치, enumeration 불확실성이 남으면 완료를 주장하지 않고 정확한 blocker list를 보고한다.

## 15. 검증

### 15.1 전수성

- 각 source enumeration count와 ledger count 일치
- source ID unique
- 모든 source record가 하나 이상의 case 또는 `record-only` bucket에 배정
- parent가 있는 commit의 diff 수는 parent 수와 일치하고, root commit의 diff 수는 empty-tree diff 1개와 일치
- 모든 PR·이슈 번호의 연속 여부가 아니라 GitHub enumeration set과 정확히 일치
- 최종 reconciliation pass에서 새 번호와 stale `updated_at` record가 0
- 모든 AI trace 파일 path와 SHA-256 일치
- 모든 document block/claim이 분류되고 source ledger에 존재

### 15.2 문서 정합성

- 이력서 case count = 포트폴리오 case count
- 각 case title byte-for-byte 일치
- 각 case의 source ID가 ledger에 존재
- 지원동기·자기소개·프로젝트 metadata·경력·교육·자격의 모든 사실 문장에 source ID 존재
- 각 포트폴리오 case에 Mermaid source와 rendered image 존재
- 수치·날짜·조건이 source claim과 일치
- `target/expected`가 measured result로 승격되지 않음
- 추측 금지 표현 검사

### 15.3 PDF

- 모든 Markdown과 PDF 생성 성공
- A4 page size
- 증명사진 품질과 비율 유지
- 한국어 font embedded
- Mermaid SVG 정상 렌더링
- empty page, overflow, 잘린 표·그림, 깨진 문자 없음
- internal TOC, source link, bookmark 유효
- 단일 PDF 또는 master index와 deterministic volume의 case 합집합이 전체 case catalog와 일치
- Ghostscript syntax validation 통과
- 원본 PDF SHA-256 불변

### 15.4 보안

- secret pattern scan
- 타인 연락처 마스킹 검사
- raw trace payload가 생성 문서에 직접 복제되지 않았는지 검사

## 16. 완료 기준

다음을 모두 만족해야 완료다.

1. 감사 snapshot의 모든 원본 레코드가 장부에 존재한다.
2. 모든 성과·문제 해결 사례가 이력서와 포트폴리오에 모두 존재한다.
3. 두 문서의 사례 제목이 정확히 일치한다.
4. 모든 포트폴리오 사례에 Mermaid 그림과 문제·해결·결과·근거가 존재한다.
5. 프로젝트 문서의 실측 성과가 축소·기각되지 않는다.
6. 문서에 없는 추측이나 새 수치가 없다.
7. 모든 검증과 coverage check가 통과한다.
8. 원본 PDF와 사용자 소유 변경이 보존된다.

## 17. 사용자 승인 결정 요약

- 계층형 전수본을 사용한다.
- 모든 성과와 모든 문제 해결을 이력서와 포트폴리오에 담는다.
- 개수와 분량에 제한을 두지 않는다.
- 모든 커밋·parent diff·PR·이슈·AI trace를 개별 기록한다.
- RPS와 문서 기록은 사실로 사용한다.
- 추측하지 않는다.
- 증명사진은 기존 이력서 PDF에서 재사용한다.
- 포트폴리오의 모든 그림은 Mermaid로 만든다.
- 지원동기는 특정 회사에 종속되지 않는 보편 문구로 작성한다.

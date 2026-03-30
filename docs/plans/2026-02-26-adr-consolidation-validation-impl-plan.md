# ADR 통합 및 검증 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 산재된 70+ ADR 문서를 `docs/01_ADR/`로 통합하고, 각 ADR의 결정사항이 실제 코드에 반영되었는지 검증하여 불일치 항목을 수정한다.

**Architecture:** Phase 1(파일 통합) → Phase 2(병렬 검증) → Phase 3(불일치 수정)의 3단계로 진행. Team 기반 병렬 에이전트로 검증 효율화.

**Tech Stack:** Bash (파일 이동), Team Coordination (병렬 검증), Git (버전 관리)

---

## Phase 1: ADR 파일 통합

### Task 1: ADR 파일 현황 분석

**Files:**
- Read: `docs/adr/`, `docs/01_ADR/`, `docs/01_ADR/`

**Step 1: 모든 ADR 파일 목록 수집**

```bash
# 전체 ADR 파일 목록 생성
find /home/maple/probabilistic-valuation-engine/docs -type f -name "ADR*.md" | sort > /tmp/all_adr_files.txt
cat /tmp/all_adr_files.txt
```

Expected: 70+ 파일 경로 출력

**Step 2: 중복 파일 식별**

```bash
# 파일명 기준 중복 식별
cat /tmp/all_adr_files.txt | xargs -I{} basename {} | sort | uniq -d > /tmp/duplicate_adr.txt
cat /tmp/duplicate_adr.txt
```

Expected: ADR-035, ADR-036 등 중복 파일명 목록

**Step 3: 각 중복 파일의 수정일자 비교**

```bash
# 중복 파일들의 수정일자 비교
for f in $(cat /tmp/duplicate_adr.txt); do
  echo "=== $f ==="
  find /home/maple/probabilistic-valuation-engine/docs -name "$f" -exec stat --format="%Y %n" {} \; | sort -rn
done
```

Expected: 각 파일의 최신 버전 식별

---

### Task 2: docs/01_ADR/ 디렉토리 정리

**Files:**
- Modify: `docs/01_ADR/` (파일 이동/삭제)

**Step 1: backup 파일 제거**

```bash
# .backup 파일 제거
find /home/maple/probabilistic-valuation-engine/docs/01_Adr -name "*.backup" -type f -delete
echo "Backup files deleted"
```

Expected: backup 파일 삭제 완료

**Step 2: 비표준 파일 별도 보관**

```bash
# 비표준 파일을 archive 디렉토리로 이동
mkdir -p /home/maple/probabilistic-valuation-engine/docs/01_ADR/_archive
mv /home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR_ENHANCEMENT_*.md /home/maple/probabilistic-valuation-engine/docs/01_ADR/_archive/ 2>/dev/null || true
mv /home/maple/probabilistic-valuation-engine/docs/01_ADR/README.md /home/maple/probabilistic-valuation-engine/docs/01_ADR/_archive/README_01_Adr.md 2>/dev/null || true
echo "Non-standard files archived"
```

Expected: 비표준 파일 이동 완료

---

### Task 3: 파일 통합 실행

**Files:**
- Move: `docs/adr/*` → `docs/01_ADR/`
- Move: `docs/01_ADR/*` → `docs/01_ADR/`

**Step 1: docs/adr/ → docs/01_ADR/ 이동**

```bash
# 최신 ADR 파일 이동 (중복 시 최신 파일 우선)
cp -u /home/maple/probabilistic-valuation-engine/docs/adr/ADR-*.md /home/maple/probabilistic-valuation-engine/docs/01_ADR/
echo "docs/adr/ files copied"
```

Expected: ADR-035, 036 파일 복사

**Step 2: docs/01_ADR/ → docs/01_ADR/ 이동**

```bash
# 01_Adr 파일들을 01_ADR로 이동 (최신 파일 우선)
cp -u /home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-*.md /home/maple/probabilistic-valuation-engine/docs/01_ADR/
echo "docs/01_ADR/ files copied"
```

Expected: 모든 ADR 파일 복사

**Step 3: 통합 결과 확인**

```bash
# 통합된 파일 수 확인
ls /home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-*.md | wc -l
ls /home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-*.md | head -20
```

Expected: 70+ ADR 파일 존재

**Step 4: 빈 디렉토리 정리**

```bash
# 원본 디렉토리 삭제 (파일 모두 이동된 경우)
rm -rf /home/maple/probabilistic-valuation-engine/docs/adr
rm -rf /home/maple/probabilistic-valuation-engine/docs/01_Adr
echo "Source directories cleaned"
```

Expected: 원본 디렉토리 삭제

**Step 5: 커밋**

```bash
git add docs/01_ADR/
git add docs/adr/ docs/01_ADR/ 2>/dev/null || true
git commit -m "refactor: consolidate all ADR documents to docs/01_ADR/

- Move docs/adr/ADR-035,036 to docs/01_ADR/
- Move docs/01_ADR/ contents to docs/01_ADR/
- Remove backup files
- Archive non-standard files to _archive/

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

Expected: Phase 1 완료 커밋

---

## Phase 2: ADR 검증 (병렬 에이전트)

### Task 4: 검증 대상 ADR 그룹핑

**Files:**
- Create: `docs/05_Reports/adr-validation-groups.json`

**Step 1: ADR 번호별 그룹 생성**

```bash
# ADR 파일을 번호 기준으로 정렬하고 3개 그룹으로 분할
ls /home/maple/probabilistic-valuation-engine/docs/01_ADR/ADR-*.md | grep -v "_archive" | sort > /tmp/sorted_adr.txt
total=$(wc -l < /tmp/sorted_adr.txt)
group_size=$((total / 3 + 1))
echo "Total: $total, Group size: $group_size"

# 그룹 파일 생성
head -n $group_size /tmp/sorted_adr.txt > /tmp/adr_group1.txt
tail -n +$((group_size + 1)) /tmp/sorted_adr.txt | head -n $group_size > /tmp/adr_group2.txt
tail -n +$((group_size * 2 + 1)) /tmp/sorted_adr.txt > /tmp/adr_group3.txt

echo "Group 1: $(wc -l < /tmp/adr_group1.txt) files"
echo "Group 2: $(wc -l < /tmp/adr_group2.txt) files"
echo "Group 3: $(wc -l < /tmp/adr_group3.txt) files"
```

Expected: 3개 그룹으로 분할 완료

---

### Task 5: Team 생성 및 검증 에이전트 배치

**Files:**
- Create: Team coordination via TeamCreate

**Step 1: 팀 생성**

Team 생성 명령:
```
TeamCreate(
  team_name: "adr-validation-team",
  description: "ADR 문서와 실제 구현 간 일치 검증"
)
```

**Step 2: 검증 태스크 생성**

각 그룹별 태스크 생성:
```
TaskCreate(subject: "ADR Group 1 검증", description: "ADR-001 ~ ADR-025 검증")
TaskCreate(subject: "ADR Group 2 검증", description: "ADR-034 ~ ADR-056 검증")
TaskCreate(subject: "ADR Group 3 검증", description: "ADR-057 ~ ADR-087 검증")
TaskCreate(subject: "아키텍처 준수 종합 검증", description: "전체 ADR의 아키텍처 원칙 준수 검증")
TaskCreate(subject: "검증 리포트 작성", description: "모든 검증 결과 취합하여 리포트 작성")
```

**Step 3: 에이전트 배치**

```
Task(team_name="adr-validation-team", name="explorer-1", subagent_type="explore", model="sonnet")
Task(team_name="adr-validation-team", name="explorer-2", subagent_type="explore", model="sonnet")
Task(team_name="adr-validation-team", name="explorer-3", subagent_type="explore", model="sonnet")
Task(team_name="adr-validation-team", name="architect", subagent_type="architect", model="opus")
Task(team_name="adr-validation-team", name="writer", subagent_type="writer", model="haiku")
```

---

### Task 6: 개별 ADR 검증 템플릿

**각 Explorer가 수행할 검증 작업:**

**Step 1: ADR 파일 읽기**

각 ADR에 대해:
```markdown
## ADR-XXX: [Title]

### Status 확인
- [ ] Accepted / Proposed / Superseded

### Context 검증
- 문제 상황이 현재도 유효한가?
- 기술적 배경이 정확한가?

### Decision 검증
- 결정된 클래스/메서드가 존재하는가?
- 패키지 구조가 일치하는가?

### Implementation 검증
- 명시된 파일 경로가 실제 존재하는가?
- 코드 스니펫이 현재 코드와 일치하는가?

### Consequences 검증
- 예상 효과가 실제 발생했는가?
- 부작용이 문서화되었는가?

### 판정
- [ ] PASS: 완전 일치
- [ ] MINOR: 사소한 불일치 (문서 업데이트 필요)
- [ ] MAJOR: 주요 불일치 (코드 또는 ADR 수정 필요)
- [ ] INVALID: ADR이 더 이상 유효하지 않음
```

---

### Task 7: 검증 결과 취합

**Files:**
- Create: `docs/05_Reports/ADR-VALIDATION-REPORT.md`

**Step 1: 리포트 템플릿 작성**

Writer 에이전트가 작성할 리포트 구조:

```markdown
# ADR 검증 리포트

## 요약
- 총 ADR 수: XX개
- PASS: XX개
- MINOR: XX개
- MAJOR: XX개
- INVALID: XX개

## 상세 검증 결과

### PASS 항목
| ADR 번호 | 제목 | 비고 |
|----------|------|------|
| ... | ... | ... |

### MINOR 불일치 (문서 수정 필요)
| ADR 번호 | 불일치 내용 | 수정 방향 |
|----------|-------------|-----------|
| ... | ... | ... |

### MAJOR 불일치 (코드/ADR 수정 필요)
| ADR 번호 | 불일치 내용 | 판단 근거 | 수정 방향 |
|----------|-------------|-----------|-----------|
| ... | ... | ... | ... |

### INVALID (폐기 대상)
| ADR 번호 | 폐기 사유 |
|----------|-----------|
| ... | ... |

## 권장 사항
1. ...
2. ...
```

---

## Phase 3: 불일치 수정

### Task 8: MAJOR 불일치 항목 수정

**Files:**
- Modify: 검증 결과에 따라 결정

**Step 1: MAJOR 항목별 수정 계획 수립**

각 MAJOR 항목에 대해:
1. ADR 내용 검토
2. 실제 코드 검토
3. 합리성 판단 (Architect 검토)
4. 수정 방향 결정
5. 수정 실행

**Step 2: 수정 실행**

```bash
# 수정 사항 커밋
git add docs/01_ADR/ADR-XXX.md  # ADR 수정 시
git add src/...                  # 코드 수정 시
git commit -m "fix: align ADR-XXX with implementation (or vice versa)

- Details...

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 9: MINOR 불일치 항목 수정

**Files:**
- Modify: `docs/01_ADR/*.md`

**Step 1: 문서만 수정**

```bash
# MINOR 항목 일괄 수정
git add docs/01_ADR/
git commit -m "docs: update ADR documents to reflect current implementation

- Fix outdated file paths
- Update code snippets
- Correct status markers

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 10: 최종 검증 및 정리

**Files:**
- Update: `docs/05_Reports/ADR-VALIDATION-REPORT.md`

**Step 1: 최종 검증 리포트 업데이트**

수정 완료 후 리포트 업데이트:
- 수정 완료 항목 표시
- 남은 이슈 정리
- 향후 관리 방안 제안

**Step 2: 최종 커밋**

```bash
git add docs/05_Reports/ADR-VALIDATION-REPORT.md
git commit -m "docs: complete ADR validation report

- Total ADRs validated: XX
- Issues fixed: XX
- Remaining items: XX

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

**Step 3: 팀 종료**

```
SendMessage(type="shutdown_request", recipient="all")
TeamDelete()
```

---

## 검증 체크리스트

### 각 Phase 완료 기준

**Phase 1 (통합):**
- [ ] 모든 ADR 파일이 `docs/01_ADR/`에 위치
- [ ] 중복 파일이 최신 버전으로 통합
- [ ] backup 파일 제거됨
- [ ] 커밋 완료

**Phase 2 (검증):**
- [ ] 모든 ADR에 대한 검증 완료
- [ ] 불일치 항목 분류 완료
- [ ] 검증 리포트 작성됨

**Phase 3 (수정):**
- [ ] MAJOR 항목 수정 완료
- [ ] MINOR 항목 수정 완료
- [ ] 최종 리포트 업데이트
- [ ] 팀 종료

---

## 참고 스킬

- @verify-adr: ADR 형식 검증
- @verify-clean-architecture: 클린 아키텍처 준수 검증
- @verify-module-structure: 모듈 구조 검증

# Guardrails Hook 연동 가이드

## 개요

Claude Code Hooks (PreToolUse, PostToolUse)와 Guardrails 시스템의 연동 방법을 설명합니다.

**버전:** 2.0.0 (Kotlin 호환)
**업데이트:** 2026-02-25

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Claude Code Hooks                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────┐         ┌─────────────────────┐           │
│  │   PreToolUse        │         │   PostToolUse       │           │
│  │   (코드 수정 전)     │         │   (코드 수정 후)     │           │
│  └──────────┬──────────┘         └──────────┬──────────┘           │
│             │                               │                        │
│             ▼                               ▼                        │
│  ┌─────────────────────┐         ┌─────────────────────┐           │
│  │ 1. 키워드 스캔      │         │ 1. 코드 스캔         │           │
│  │ 2. 가드레일 매칭    │         │ 2. Layer 1: Regex  │           │
│  │ 3. DON'T 주입       │         │ 3. Layer 2: Keywords│           │
│  │ 4. AI 판단          │         │ 4. AI 판단           │           │
│  └─────────────────────┘         └─────────────────────┘           │
│             │                               │                        │
│             └───────────┬───────────────────┘                        │
│                         ▼                                            │
│              ┌─────────────────────┐                                │
│              │  INDEX.json         │                                │
│              │  - 27+ 패턴         │                                │
│              │  - keywords 기반     │                                │
│              │  - AI 판단 4개      │                                │
│              └─────────────────────┘                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## PreToolUse (코드 작성 전)

### 목적

코드 수정 시도 전에 안티패턴 사용을 미리 차단합니다.

### 작업 흐름

```
[사용자 입력] → [Hook 감지] → [Import/키워드 스캔]
                                    ↓
                          [INDEX.json keywords 매칭]
                                    ↓
                          ┌─────────┴─────────┐
                          │                   │
                    [Regex 패턴]      [AI 판단 패턴]
                          │                   │
                  [즉시 차단]      [가드레일 md 주입]
                                          │
                                    [AI 분석]
                                          │
                                    [위반 시 차단]
```

### 구현 가이드

```javascript
// .claude/hooks/pre-tool-use.js (Pseudo-code)

async function preToolUse(input) {
  const { toolName, toolInput } = input;

  // 1. 코드 수정 도구만 필터링
  if (!isCodeModificationTool(toolName)) return;

  // 2. 대상 파일 추출
  const filePath = extractFilePath(toolInput);
  if (!filePath) return;

  // 3. 파일 내용 읽기 (import, 키워드)
  const content = await readFile(filePath);
  const imports = extractImports(content);
  const keywords = extractKeywords(content);

  // 4. INDEX.json 로드
  const index = await loadGuardrailsIndex('docs/guardrails/INDEX.json');

  // 5. Layer 1: Regex 매칭 (즉시 차단)
  for (const [patternId, pattern] of Object.entries(index.patterns)) {
    if (pattern.regex) {
      const regex = new RegExp(pattern.regex, 'gm');
      const matches = content.match(regex);
      if (matches && !isExempt(patternId, filePath)) {
        return blockAction({
          reason: `Guardrail violation: ${patternId}`,
          message: pattern.description,
          file: filePath,
          line: findMatchLine(content, matches[0]),
          severity: pattern.severity,
          remediation: `See: docs/guardrails/${pattern.file}`
        });
      }
    }
  }

  // 6. Layer 2: Keywords 매칭 (AI 판단)
  const aiPatterns = Object.entries(index.patterns)
    .filter(([_, p]) => p.aiJudgment);

  for (const [patternId, pattern] of aiPatterns) {
    const keywordMatch = pattern.keywords.some(kw =>
      keywords.includes(kw) || imports.some(imp => imp.includes(kw))
    );

    if (keywordMatch && !isExempt(patternId, filePath)) {
      // 가드레일 md 파일에서 DON'T 섹션 추출
      const guardrailContent = await readFile(`docs/guardrails/${pattern.file}`);
      const dontSection = extractDontSection(guardrailContent);

      // AI에게 코드 + DON'T 섹션 전달
      const isViolation = await aiAnalyze({
        code: content,
        dontSection: dontSection,
        context: `Check if this code violates guardrail ${patternId}`
      });

      if (isViolation) {
        return blockAction({
          reason: `AI-detected guardrail violation: ${patternId}`,
          message: pattern.description,
          file: filePath,
          severity: pattern.severity,
          remediation: `See: docs/guardrails/${pattern.file}`
        });
      }
    }
  }

  // 7. 위반 없으면 진행 허용
  return allowAction();
}

// 예외 경로 확인
function isExempt(patternId, filePath) {
  const index = getGuardrailsIndex();
  const exemptions = index.exclusions.patternExemptions[patternId];
  if (!exemptions) return false;

  return exemptions.some(exempt =>
    exempt === 'testPaths' && isTestPath(filePath)
  );
}

// AI 분석 (Layer 2)
async function aiAnalyze({ code, dontSection, context }) {
  const prompt = `
Context: ${context}

DON'T Section (Anti-Patterns):
${dontSection}

Code to Analyze:
${code}

Question: Does the code violate any DON'T patterns above?
Answer: YES or NO with specific line references.
`;

  const response = await callClaudeAPI(prompt);
  return response.startsWith('YES');
}
```

---

## PostToolUse (코드 작성 후)

### 목적

생성된 코드를 검증하여 베스트 프랙티스 준수 여부를 확인합니다.

### 작업 흐름

```
[코드 생성 완료] → [Hook 감지] → [생성된 코드 스캔]
                                          ↓
                                ┌─────────┴─────────┐
                                │                   │
                          [Layer 1: Regex]   [Layer 2: Keywords]
                                │                   │
                          [매칭 시 즉시 보고]  [가드레일 md 주입]
                                                      │
                                                  [AI 분석]
                                                      │
                              ┌─────────────────────┴─────────┐
                              │                               │
                        [DO 섹션 비교]                 [위반 보고]
                              │
                        [개선 제안]
```

### 구현 가이드

```javascript
// .claude/hooks/post-tool-use.js (Pseudo-code)

async function postToolUse(input) {
  const { toolName, toolInput, toolResponse } = input;

  // 1. 코드 생성 도구만 필터링
  if (!isCodeGenerationTool(toolName)) return;

  // 2. 생성된 코드 추출
  const generatedCode = extractGeneratedCode(toolResponse);
  if (!generatedCode) return;

  // 3. INDEX.json 로드
  const index = await loadGuardrailsIndex('docs/guardrails/INDEX.json');

  const violations = [];
  const improvements = [];

  // 4. Layer 1: Regex 검증
  for (const [patternId, pattern] of Object.entries(index.patterns)) {
    if (pattern.regex) {
      const regex = new RegExp(pattern.regex, 'gm');
      const matches = generatedCode.match(regex);
      if (matches) {
        violations.push({
          patternId,
          severity: pattern.severity,
          message: pattern.description,
          matches: matches,
          remediation: `docs/guardrails/${pattern.file}`
        });
      }
    }
  }

  // 5. Layer 2: Keywords + AI 판단
  const keywords = extractKeywords(generatedCode);
  const aiPatterns = Object.entries(index.patterns)
    .filter(([_, p]) => p.aiJudgment);

  for (const [patternId, pattern] of aiPatterns) {
    const keywordMatch = pattern.keywords.some(kw => keywords.includes(kw));

    if (keywordMatch) {
      const guardrailContent = await readFile(`docs/guardrails/${pattern.file}`);
      const dontSection = extractDontSection(guardrailContent);
      const doSection = extractDoSection(guardrailContent);

      const analysis = await aiAnalyzeWithImprovement({
        code: generatedCode,
        dontSection,
        doSection,
        context: patternId
      });

      if (analysis.violation) {
        violations.push({
          patternId,
          severity: pattern.severity,
          message: analysis.reason,
          line: analysis.line,
          remediation: `docs/guardrails/${pattern.file}`
        });
      }

      if (analysis.improvement) {
        improvements.push({
          patternId,
          suggestion: analysis.suggestion,
          bestPractice: analysis.bestPractice,
          reference: `docs/guardrails/${pattern.file}`
        });
      }
    }
  }

  // 6. 보고서 생성
  if (violations.length > 0 || improvements.length > 0) {
    return generateReport({
      violations,
      improvements,
      summary: generateSummary(violations, improvements)
    });
  }

  return passVerification();
}

// AI 분석 + 개선 제안 (Layer 2)
async function aiAnalyzeWithImprovement({ code, dontSection, doSection, context }) {
  const prompt = `
Context: Guardrail verification for ${context}

DON'T Section (Anti-Patterns):
${dontSection}

DO Section (Best Practices):
${doSection}

Generated Code:
${code}

Tasks:
1. Check if code violates any DON'T patterns
2. Suggest improvements based on DO section
3. Provide specific line references

Format:
VIOLATION: YES/NO
REASON: (if violation)
LINE: (line number)
IMPROVEMENT: (suggested best practice)
BEST_PRACTICE: (relevant DO section excerpt)
`;

  const response = await callClaudeAPI(prompt);
  return parseAIResponse(response);
}
```

---

## INDEX.json 데이터 구조

### 패턴 항목

```json
{
  "pattern-id": {
    "regex": "(?:optional|java/kotlin regex pattern)",  // Layer 1: 즉시 매칭
    "file": "path/to/guardrail.md",                      // 가드레일 문서 경로
    "severity": "critical|warning",                      // 심각도
    "id": "GR-XXX",                                      // 고유 ID
    "description": "Human-readable description",
    "keywords": ["keyword1", "keyword2"],                // Layer 2: AI 판단
    "languages": ["java", "kotlin"],                    // 지원 언어
    "aiJudgment": true/false,                           // AI 판단 여부
    "exemptions": ["allowedPattern1", "allowedPattern2"] // 예외 패턴
  }
}
```

### AI 판단 패턴 (4개)

| 패턴 ID | 카테고리 | 이유 | 키워드 |
|---------|----------|------|--------|
| GR-003 | AOP Self-Invocation | `this.` 호출 맥락 분석 필요 | AOP, Facade, this. |
| GR-004 | Lambda Hell | 중첩 깊이/줄 수 시각적 판단 필요 | lambda, nested, { |
| GR-005 | Null Check | `if (x != null)` vs Optional 사용 맥락 | Optional, null, if ( |
| GR-RESILIENCE-002 | Marker Interface | 상속 트리 분석 필요 | Exception, extends, Marker |

---

## 예외 (Exemptions) 처리

### 테스트 경로

```javascript
const testPathPatterns = [
  /src\/test\/.*/,
  /.*\/test\/.*/,
  /chaos-test/
];

function isTestPath(filePath) {
  return testPathPatterns.some(pattern => pattern.test(filePath));
}
```

### 허용 패턴 (allowedPatterns)

```javascript
const allowedPatterns = [
  "DefaultLogicExecutor",    // LogicExecutor 구현체 내부
  "TraceAspect",             // AOP 순환참조 방지
  "ExecutionPipeline",       // 파이프라인 내부
  "DonationOutbox"           // JPA Entity
];

function isAllowedPattern(content, pattern) {
  return content.includes(pattern);
}
```

---

## 보고서 형식

### PreToolUse 차단 보고서

```markdown
## 🔴 Guardrail Violation Detected

**Pattern ID:** GR-001
**Severity:** Critical
**File:** src/main/kotlin/com/example/Service.java:42

### Description
Direct try-catch usage prohibited. Use LogicExecutor instead.

### Violation
\`\`\`java
// Line 42
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}
\`\`\`

### Remediation
See: docs/guardrails/backend/spring/logic-executor.md

\`\`\`java
// Good
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Service", "FindById", id)
);
\`\`\`

**Action Blocked** - Modify code to proceed.
```

### PostToolUse 검증 보고서

```markdown
## ✅ Guardrails Verification Report

### Summary
- **Violations:** 0
- **Improvements:** 2

### Improvement Suggestions

#### 1. GR-005: Optional Chaining Opportunity
**Location:** Service.kt:58

**Current:**
\`\`\`kotlin
if (user != null) {
    return process(user)
}
return null
\`\`\`

**Suggested:**
\`\`\`kotlin
return user?.let { process(it) }
\`\`\`

**Reference:** docs/guardrails/backend/spring/optional-chaining.md

---

#### 2. GR-AOP-004: Sensitive Data Logging Risk
**Location:** LoginRequest.kt:15

**Concern:** Default toString() may expose API key

**Suggested:**
\`\`\`kotlin
data class LoginRequest(val apiKey: String, val userIgn: String) {
    override fun toString(): String {
        return "LoginRequest[apiKey=${maskApiKey(apiKey)}, userIgn=$userIgn]"
    }

    private fun maskApiKey(key: String): String {
        return if (key.length < 8) "****" else "${key.take(4)}****${key.takeLast(4)}"
    }
}
\`\`\`

**Reference:** docs/guardrails/backend/spring/aop-facade.md

### ✅ Verification Passed
Code follows guardrails best practices.
```

---

## 검증 명령어

### Hook 동작 테스트

```bash
# PreToolUse 테스트: try-catch 사용 시 차단 확인
echo "try { }" | claude-code-hook-test --pre-tool-use

# PostToolUse 테스트: lambda 생성 개선 제안 확인
echo "users.map { u -> u.name }.filter { it != null }" | claude-code-hook-test --post-tool-use
```

### INDEX.json 무결성 검사

```bash
# 모든 패턴에 필수 필드 있는지 확인
cat docs/guardrails/INDEX.json | jq '.patterns | to_entries[] | select(.value.file == null)'

# AI 판단 패턴 4개 확인
cat docs/guardrails/INDEX.json | jq '.patterns | to_entries[] | select(.value.aiJudgment == true) | .key'

# 중복 ID 검사
cat docs/guardrails/INDEX.json | jq '.patterns | to_entries | map(.value.id) | group_by(.) | map(select(length > 1))'
```

---

## 관련 문서

- **INDEX.json:** `docs/guardrails/INDEX.json` - 전체 패턴 인덱스
- **INDEX.md:** `docs/guardrails/INDEX.md` - 카테고리별 파일 목록
- **CLAUDE.md:** Section 11, 12, 15, 18, 20 - 코어 규칙

---

## 변경 로그

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| 2.0.0 | 2026-02-25 | Kotlin 호환, AI 판단 4개 패턴 추가, languages 필드 도입 |
| 1.3.0 | 2025-02-25 | 기존 27개 패턴 등록 |

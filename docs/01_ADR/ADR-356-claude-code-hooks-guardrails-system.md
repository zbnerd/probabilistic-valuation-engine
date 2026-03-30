# ADR-007: Claude Code Hooks Guardrails System

**Status**: Accepted
**Date**: 2026-02-28
**Related PRs**: #393, #394, #396
**Related ADRs**: N/A

---

## 1. 배경 (Background)

PR #393-396에서 Claude Code Hooks 기반의 가드레일(Guardrails) 시스템이 도입되었다. 이 시스템은 AI 어시스턴트가 코드를 작성할 때 아키텍처 규칙과 코딩 표준을 준수하도록 자동으로 검증한다. 이는 프로젝트 수준의 중요한 아키텍처 결정사항으로 별도의 ADR로 문서화할 필요가 있다.

---

## 2. 문제 (Problem)

### 2.1 코드 품질 관리 과제

- AI 어시스턴트가 작성한 코드가 프로젝트 표준을 준수하는지 수동 검증 어려움
- 반복적인 코드 리뷰 지적 사항 (FQCN 사용, try-catch 남용 등)
- 아키텍처 규칙 위반이 PR 단계에서만 발견됨
- 일관된 코딩 스타일 유지의 어려움

### 2.2 기존 접근 방식의 한계

- **Lint 도구만으로는 부족**: 정적 분석은 구문적 오류만 감지
- **수동 코드 리뷰**: 시간 소모적이며 일관성 부족
- **CI/CD 검증**: PR 단계에서만 피드백 제공

---

## 3. 결정 (Decision)

### 3.1 2계열 검증 구조

```
┌─────────────────────────────────────────────────────────────┐
│                    Claude Code Hook                          │
│                    (PreToolUse)                              │
└──────────────────────┬──────────────────────────────────────┘
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
┌─────────────────────┐   ┌─────────────────────┐
│   Layer 1: Regex    │   │    Layer 2: AI      │
│   (즉시 차단)        │   │    (판단 후 차단)    │
│                     │   │                     │
│ • FQCN 패턴 감지     │   │ • 아키텍처 위반      │
│ • try-catch 패턴     │   │ • 복잡한 규칙 검증   │
│ • @deprecated 사용   │   │ • 컨텍스트 기반 판단 │
└─────────────────────┘   └─────────────────────┘
          │                         │
          ▼                         ▼
   ┌──────────┐            ┌──────────────┐
   │  즉시     │            │   AI 판단     │
   │  차단     │            │   후 결정     │
   └──────────┘            └──────────────┘
```

### 3.2 가드레일 카테고리

| 카테고리 | 설명 | 예시 패턴 |
|----------|------|----------|
| **Architecture** | 아키텍처 규칙 준수 | 모듈 의존성 방향, DIP 위반 |
| **Backend** | 백엔드 코딩 표준 | FQCN 사용, raw type 사용 |
| **Testing** | 테스트 작성 규칙 | 통합 테스트 차단, Thread.sleep 금지 |
| **Infra** | 인프라 구성 규칙 | 하드코딩된 URL/포트 |
| **Kotlin** | Kotlin 코딩 표준 | data class 사용, null 처리 |
| **Java** | Java 코딩 표준 | try-catch 대신 LogicExecutor |
| **Security** | 보안 규칙 | 민감정보 하드코딩 금지 |
| **Documentation** | 문서화 규칙 | ADR 형식 준수 |
| **General** | 일반 코딩 규칙 | 주석 규칙, 네이밍 |

### 3.3 Kotlin 호환성

가드레일 시스템은 Java와 Kotlin 모두 지원한다:

```json
{
  "id": "GUARD-001",
  "name": "No FQCN in Implementation",
  "languages": ["java", "kotlin"],
  "pattern": "(?:^|\\s)([a-z]+\\.)+[A-Z][a-zA-Z0-9]*(?:\\s|\\(|\\)|;|$)",
  "layer": 1,
  "severity": "warning"
}
```

### 3.4 Hook 구현 가이드

#### PreToolUse Hook (사전 검증)

```bash
#!/bin/bash
# .claude/hooks/pre-tool-use.sh

# Layer 1: Regex 기반 즉시 차단
if echo "$TOOL_INPUT" | grep -qE "$FORBIDDEN_PATTERN"; then
    echo "BLOCK: Forbidden pattern detected"
    exit 2
fi

# Layer 2: AI 판단 (복잡한 규칙)
if requires_ai_judgment "$TOOL_INPUT"; then
    RESULT=$(ask_ai_for_judgment "$TOOL_INPUT")
    if [ "$RESULT" = "BLOCK" ]; then
        echo "BLOCK: AI judgment - $REASON"
        exit 2
    fi
fi

exit 0  # Allow
```

#### PostToolUse Hook (사후 검증)

```bash
#!/bin/bash
# .claude/hooks/post-tool-use.sh

# 파일 생성/수정 후 검증
if [ "$TOOL_NAME" = "Write" ] || [ "$TOOL_NAME" = "Edit" ]; then
    verify_guardrails "$TOOL_INPUT" "$TOOL_RESPONSE"
fi
```

### 3.5 가드레일 버전 관리

```
docs/guardrails/
├── INDEX.json          # 가드레일 메타데이터 (버전, 개수)
├── INDEX.md            # 가드레일 목록 문서
├── HOOK_GUIDE.md       # Hook 구현 가이드
└── patterns/           # 개별 가드레일 정의
    ├── GUARD-001.md
    ├── GUARD-002.md
    └── ...
```

**INDEX.json 구조:**
```json
{
  "version": "2.0.0",
  "totalPatterns": 88,
  "categories": {
    "Architecture": 12,
    "Backend": 15,
    "Testing": 10,
    "Infra": 8,
    "Kotlin": 18,
    "Java": 12,
    "Security": 8,
    "Documentation": 5
  },
  "lastUpdated": "2026-02-28"
}
```

---

## 4. 결과 (Consequences)

### 4.1 긍정적 효과

- **즉각적 피드백**: 코드 작성 시점에 규칙 위반 감지
- **일관된 품질**: 모든 코드가 동일한 기준으로 검증
- **리뷰 효율성**: 자동 검증으로 리뷰어 부담 감소
- **학습 효과**: AI 어시스턴트가 규칙을 학습하여 반복 오류 감소

### 4.2 Layer 분리의 이점

| 측면 | Layer 1 (Regex) | Layer 2 (AI) |
|------|-----------------|--------------|
| **속도** | 즉시 (< 1ms) | 지연 (100-500ms) |
| **정확도** | 패턴 매칭 한계 | 컨텍스트 이해 가능 |
| **비용** | 무료 | API 호출 비용 |
| **용도** | 명확한 규칙 위반 | 복잡한 판단 필요 |

### 4.3 주의사항

- **False Positive**: 정상 코드가 차단될 가능성
- **우회 가능성**: 가드레일을 우회하는 방법 존재
- **유지보수**: 새로운 패턴에 대한 가드레일 업데이트 필요

### 4.4 확장 계획

1. **Phase 1 (완료)**: 88개 기본 패턴 등록
2. **Phase 2 (진행)**: Kotlin 특화 패턴 추가
3. **Phase 3 (계획)**: 프로젝트별 커스텀 가드레일 지원
4. **Phase 4 (계획)**: AI 학습을 통한 가드레일 자동 생성

---

## 5. 검증 방법

### 5.1 가드레일 검증 스킬

```bash
# 모든 가드레일 검증
/verify-guardrails
```

검증 항목:
- INDEX.json 무결성 확인
- 가드레일 md 파일 구조 검증
- 파일 참조 무결성 확인
- 정규식 패턴 유효성 검사

### 5.2 수동 테스트

```bash
# 가드레일 트리거 테스트
echo "org.springframework.stereotype.Service service = new Service();" | \
  ./claude/hooks/pre-tool-use.sh
# Expected: BLOCK
```

---

## 6. 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-02-28 | 초안 작성 | Claude (Architect) |
| 2026-02-28 | PR #393-396 분석 기반 전략 수립 | Claude Team |
| 2026-02-28 | v2.0.0 (88개 패턴) 릴리즈 | Claude Team |

---

## 7. 참조 (References)

- [Guardrails INDEX.md](../guardrails/INDEX.md)
- [Guardrails HOOK_GUIDE.md](../guardrails/HOOK_GUIDE.md)
- [Guardrails INDEX.json](../guardrails/INDEX.json)
- PR #393: Guardrails 시스템 도입
- PR #394: Kotlin 호환성 추가
- PR #396: Layer 2 AI 판단 구현
- [Claude Code Hooks Documentation](https://docs.anthropic.com/claude-code/hooks)

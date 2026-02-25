#!/bin/bash
# PreToolUse Hook - 2-Layer Guardrail System
# Layer 1: Regex 패턴 매칭 (빠른 차단)
# Layer 2: AI Context Injection (복잡한 패턴은 AI가 판단)
# 문서: https://code.claude.com/docs/en/hooks

CWD="${CLAUDE_WORKING_DIRECTORY:-$(pwd)}"
GUARDRAIL_INDEX="$CWD/docs/guardrails/INDEX.json"
GUARDRAIL_DIR="$CWD/docs/guardrails"
INPUT=$(cat)

# JSON 파싱 (jq 사용)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty' 2>/dev/null || echo "")

# ============================================
# Bash 도구 제어
# ============================================
if [ "$TOOL_NAME" = "Bash" ]; then
  COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null || echo "")

  # 통합테스트 차단
  if echo "$COMMAND" | grep -qiE 'integrationTest|e2eTest|end-to-end'; then
    echo -e '\n❌ 통합테스트는 차단되었습니다. unit 테스트만 실행하세요: ./gradlew test\n' >&2
    exit 2
  fi

  # Git push 보호 - develop/master 직접 푸시 차단
  if echo "$COMMAND" | grep -qE 'git\s+push'; then
    CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

    # develop/master로 직접 푸시 차단 (정확한 패턴 매칭)
    # 허용: git push origin feature/xxx, git push -u origin feature/xxx
    # 차단: git push develop, git push origin develop, git push origin master
    if echo "$COMMAND" | grep -qE 'git\s+push\s+develop\b|git\s+push\s+origin\s+develop\b|git\s+push\s+origin\s+master\b|git\s+push\s+-u\s+origin\s+develop\b|git\s+push\s+-u\s+origin\s+master\b'; then
      echo -e '\n❌ 브랜치에 직접 푸시하는 것은 금지되었습니다!\n' >&2
      echo "현재 브랜치: $CURRENT_BRANCH" >&2
      echo '' >&2
      echo '✅ 올바른 workflow (feature/fix):' >&2
      echo '1. git checkout develop' >&2
      echo '2. git checkout -b feature/fix-xxx-issue' >&2
      echo '3. git commit -m "feat: xxx"' >&2
      echo '4. git push origin feature/fix-xxx-issue' >&2
      echo '5. gh pr create --base develop' >&2
      echo '' >&2
      exit 2
    fi
  fi
fi

# ============================================
# Write/Edit 도구 - 2-Layer 가드레일 체크
# ============================================
if [ "$TOOL_NAME" = "Write" ] || [ "$TOOL_NAME" = "Edit" ]; then
  FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null || echo "")

  # Java/Kotlin 파일만 체크
  if echo "$FILE_PATH" | grep -qE '\.(java|kt)$'; then
    # 인덱스 파일 존재 확인
    if [ ! -f "$GUARDRAIL_INDEX" ]; then
      exit 0
    fi

    # 작성할 내용 추출
    if [ "$TOOL_NAME" = "Write" ]; then
      CONTENT=$(echo "$INPUT" | jq -r '.tool_input.content // empty' 2>/dev/null || echo "")
    else
      CONTENT=$(echo "$INPUT" | jq -r '.tool_input.new_string // empty' 2>/dev/null || echo "")
    fi

    # 내용이 없으면 통과
    if [ -z "$CONTENT" ]; then
      exit 0
    fi

    # 테스트 파일 여부 확인
    IS_TEST=0
    if echo "$FILE_PATH" | grep -qE 'src/test/.*\.java$|src/test/.*\.kt$|chaos-test'; then
      IS_TEST=1
    fi

    # 허용된 패턴 확인
    IS_ALLOWED=0
    ALLOWED_PATTERNS=$(jq -r '.allowedPatterns | join("|")' "$GUARDRAIL_INDEX" 2>/dev/null)
    if [ -n "$ALLOWED_PATTERNS" ] && echo "$CONTENT" | grep -qE "$ALLOWED_PATTERNS"; then
      IS_ALLOWED=1
    fi

    # ============================================
    # Layer 1: Regex 패턴 매칭 (즉시 차단)
    # ============================================
    PATTERN_KEYS=$(jq -r '.patterns | keys[]' "$GUARDRAIL_INDEX" 2>/dev/null)

    for KEY in $PATTERN_KEYS; do
      PATTERN_REGEX=$(jq -r ".patterns[\"$KEY\"].regex" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_SEVERITY=$(jq -r ".patterns[\"$KEY\"].severity" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_ID=$(jq -r ".patterns[\"$KEY\"].id" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_FILE=$(jq -r ".patterns[\"$KEY\"].file" "$GUARDRAIL_INDEX" 2>/dev/null)

      # 패턴 매칭 확인
      if [ -n "$PATTERN_REGEX" ] && echo "$CONTENT" | grep -qE "$PATTERN_REGEX"; then
        # 테스트 파일 제외 확인
        EXEMPT=0
        if [ "$IS_TEST" -eq 1 ]; then
          EXEMPTIONS=$(jq -r ".exclusions.patternExemptions[\"$KEY\"]" "$GUARDRAIL_INDEX" 2>/dev/null)
          if [ "$EXEMPTIONS" != "null" ] && echo "$EXEMPTIONS" | grep -q "testPaths"; then
            EXEMPT=1
          fi
        fi

        # 허용 패턴이면 제외
        if [ "$IS_ALLOWED" -eq 1 ]; then
          EXEMPT=1
        fi

        # 차단 또는 경고
        if [ "$EXEMPT" -eq 0 ]; then
          if [ "$PATTERN_SEVERITY" = "critical" ]; then
            echo "" >&2
            echo "🚨 [$PATTERN_ID] 가드레일 위반 감지 (critical)" >&2
            echo "   → 가드레일 문서: docs/guardrails/$PATTERN_FILE" >&2
            echo "" >&2
            exit 2
          else
            echo "" >&2
            echo "⚠️ [$PATTERN_ID] 가드레일 위반 감지 (warning)" >&2
            echo "   → 가드레일 문서: docs/guardrails/$PATTERN_FILE" >&2
            echo "" >&2
          fi
        fi
      fi
    done

    # ============================================
    # Layer 2: AI Context Injection (동적 로딩)
    # ============================================
    # INDEX.json에서 aiJudgment: true 패턴을 동적으로 읽어
    # keywords 매칭 시 가드레일 문서를 컨텍스트에 주입

    CONTEXT_HINTS=""

    # aiJudgment 패턴 목록 조회
    AI_PATTERN_KEYS=$(jq -r '.patterns | to_entries[] | select(.value.aiJudgment == true) | .key' "$GUARDRAIL_INDEX" 2>/dev/null)

    for KEY in $AI_PATTERN_KEYS; do
      # 패턴 정보 조회
      PATTERN_ID=$(jq -r ".patterns[\"$KEY\"].id" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_KEYWORDS=$(jq -r ".patterns[\"$KEY\"].keywords | join(\"|\")" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_FILE=$(jq -r ".patterns[\"$KEY\"].file" "$GUARDRAIL_INDEX" 2>/dev/null)
      PATTERN_DESC=$(jq -r ".patterns[\"$KEY\"].description" "$GUARDRAIL_INDEX" 2>/dev/null)

      # keywords 매칭 확인
      if [ -n "$PATTERN_KEYWORDS" ] && echo "$CONTENT" | grep -qiE "$PATTERN_KEYWORDS"; then
        CONTEXT_HINTS="${CONTEXT_HINTS}
📌 [$PATTERN_ID] AI 판단 필요
   → $PATTERN_DESC
   → docs/guardrails/$PATTERN_FILE 참조"
      fi
    done

    # 추가: 복합 조건 패턴 (하드코딩 필요한 경우)

    # GR-003: AOP self-invocation (@Service 내부 this. 호출)
    if echo "$CONTENT" | grep -qE '@Service|@Component|@Repository' && echo "$CONTENT" | grep -q 'this\.'; then
      CONTEXT_HINTS="${CONTEXT_HINTS}
📌 [GR-003] AOP self-invocation 의심: this. 키워드가 Spring Bean 내부에서 사용됨
   → Facade 패턴으로 리팩토링 권장 (외부 호출로 변경)"
    fi

    # GR-004: Lambda Hell (람다 내부 분기문)
    if echo "$CONTENT" | grep -qE '->\s*\{[^}]{20,}'; then
      if echo "$CONTENT" | grep -qE '->\s*\{[^}]*if[^}]*return|->\s*\{[^}]*when'; then
        CONTEXT_HINTS="${CONTEXT_HINTS}
📌 [GR-004] Lambda Hell 의심: 복잡한 람다 블록 (분기문 포함)
   → 3줄 초과 람다는 private method로 추출 권장"
      fi
    fi

    # GR-RESILIENCE-002: Exception extends (Marker Interface 없음)
    if echo "$CONTENT" | grep -qE 'class\s+\w+Exception\s*:?\s*extends'; then
      if ! echo "$CONTENT" | grep -qE 'ClientBaseException|ServerBaseException|BaseException'; then
        CONTEXT_HINTS="${CONTEXT_HINTS}
📌 [GR-RESILIENCE-002] Marker Interface 없는 Exception 정의
   → CircuitBreakerIgnoreMarker 또는 CircuitBreakerRecordMarker 상속 권장"
      fi
    fi

    # 컨텍스트 힌트 출력 (AI가 참고하도록)
    if [ -n "$CONTEXT_HINTS" ]; then
      echo "" >&2
      echo "📋 [Layer 2] AI Context - 가드레일 참고 사항:" >&2
      echo "$CONTEXT_HINTS" >&2
      echo "" >&2
    fi
  fi
fi

# 허용
exit 0

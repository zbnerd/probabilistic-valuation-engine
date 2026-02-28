#!/bin/bash
set -e

INPUT=$(cat)

# 구현 관련 작업인지 확인 (간단한 키워드 매칭)
LAST_MSG=$(echo "$INPUT" | node -e "
  let d='';
  process.stdin.on('data',c=>d+=c);
  process.stdin.on('end',()=>{
    try {
      const input = JSON.parse(d);
      console.log(input.last_assistant_message || '');
    } catch(e) {
      console.log('');
    }
  });
")

# 구현 키워드가 없으면 단순 조회 → 통과
if ! echo "$LAST_MSG" | grep -qiE '구현|개발|코드|작성|implement|develop|refactor|fix|write.*file'; then
  exit 0
fi

# 구현 작업이었다면 검증
CWD=$(echo "$INPUT" | node -e "
  let d='';
  process.stdin.on('data',c=>d+=c);
  process.stdin.on('end',()=>{
    try { console.log(JSON.parse(d).cwd || '.'); }
    catch(e) { console.log('.'); }
  });
")

# 기본값 설정
if [ -z "$CWD" ] || [ ! -d "$CWD" ]; then
  CWD="/home/maple/MapleExpectation"
fi

cd "$CWD"

FAILURES=""

# ADR 문서 확인
ADR_COUNT=$(find "$CWD/docs/adr" -name "*.md" 2>/dev/null | wc -l)
if [ "$ADR_COUNT" -eq 0 ]; then
  FAILURES="$FAILURES\n  ❌ ADR 문서가 없습니다 (docs/adr/)"
fi

# Unit test 확인
if [ ! -d "$CWD/src/test" ] && [ ! -d "$CWD/module-app/src/test" ]; then
  FAILURES="$FAILURES\n  ❌ Unit test 디렉토리가 없습니다"
fi

# ============================================================================
# PfastTest 통과 확인 추가 (AI 실수를 기계적으로 검증)
# ============================================================================
echo -e '\n🧪 PfastTest 실행 중... (AI 실수 방지)' >&2

TEST_OUTPUT=$(timeout 300 ./gradlew test -PfastTest --no-daemon -q 2>&1)
TEST_EXIT=$?

if [ $TEST_EXIT -ne 0 ]; then
  echo -e "\n🚨PfastTest 실패! AI가 실수를 했습니다:\n" >&2
  echo "$TEST_OUTPUT" | tail -50 >&2
  FAILURES="$FAILURES\n  ❌ PfastTest 실패 - 위 오류를 수정해야 합니다"
fi

if [ -n "$FAILURES" ]; then
  echo -e "\n🚨 Stop Hook 검증 실패:$FAILURES\n" >&2
  exit 2
fi

echo -e '✅ PfastTest 통과\n' >&2

# ============================================================================
# DoD (Definition of Done) 검증
# ============================================================================
echo -e '\n🔍 DoD 검증 중...' >&2

CHANGED=$(git diff --name-only HEAD 2>/dev/null)
if [ -n "$CHANGED" ]; then

  # 1. 컴파일 체크
  echo "  📦 컴파일 체크..." >&2
  if ! ./gradlew compileJava compileKotlin --no-daemon -q 2>&1; then
    echo "❌ 컴파일 실패. 연결 안 된 코드 있음." >&2
    exit 2
  fi

  # 2. TODO/FIXME/placeholder/skeleton 체크
  echo "  📝 미완성 코드 체크..." >&2
  TODOS=$(echo "$CHANGED" | xargs grep -ln 'TODO\|FIXME\|placeholder\|NotImplemented\|skeleton' 2>/dev/null || true)
  if [ -n "$TODOS" ]; then
    echo "❌ 미완성 코드 발견:" >&2
    echo "$TODOS" >&2
    exit 2
  fi

  # 3. 새 Java/Kotlin 클래스가 어딘가에서 사용되는지 체크
  echo "  🔗 연결되지 않은 클래스 체크..." >&2
  echo "$CHANGED" | grep -E '\.(java|kt)$' | while read f; do
    if [ -n "$f" ] && [ -f "$f" ]; then
      CLASS=$(basename "$f" | sed 's/\.\(java\|kt\)$//')
      # 테스트 파일은 제외
      if echo "$f" | grep -qE "(Test|Spec)\.(java|kt)$"; then
        continue
      fi
      # @Scheduled 컴포넌트는 Spring이 호출하므로 제외
      if grep -q '@Scheduled' "$f" 2>/dev/null; then
        continue
      fi
      # batch/, scheduler/ 디렉토리의 컴포넌트는 Spring 스캔 대상이므로 제외
      if echo "$f" | grep -qE '/(batch|scheduler)/'; then
        continue
      fi
      USAGE=$(grep -rl "$CLASS" --include="*.java" --include="*.kt" --include="*.yml" --include="*.xml" . 2>/dev/null | grep -v "$f" | grep -v "build/" | head -1 || true)
      if [ -z "$USAGE" ]; then
        echo "❌ 연결 안 된 클래스: $CLASS ($f)" >&2
        exit 2
      fi
    fi
  done

  # 4. Stateless 위반 체크
  echo "  🌐 Stateless 위반 체크..." >&2
  STATEFUL=$(echo "$CHANGED" | grep -E '\.(java|kt)$' | xargs grep -ln 'HttpSession\|@SessionScope\|@SessionAttributes' 2>/dev/null || true)
  if [ -n "$STATEFUL" ]; then
    echo "❌ Stateless 위반: $STATEFUL" >&2
    exit 2
  fi

  # 5. LLM 안티패턴: 테스트 우회 (@Disabled, @Ignore, @Tag("flaky"))
  echo "  🚫 테스트 우회 패턴 체크..." >&2
  DISABLED=$(echo "$CHANGED" | grep -E '\.(java|kt)$' | xargs grep -ln '@Disabled\|@Ignore\|@Tag("flaky")' 2>/dev/null || true)
  if [ -n "$DISABLED" ]; then
    echo "❌ 테스트 우회 감지 (@Disabled/@Ignore/@Tag(\"flaky\")):" >&2
    echo "$DISABLED" >&2
    exit 2
  fi

  # 6. LLM 안티패턴: return null; placeholder (주석과 함께 있는 경우)
  echo "  🔍 placeholder 구현 체크..." >&2
  PLACEHOLDER=$(echo "$CHANGED" | grep -E '\.(java|kt)$' | xargs grep -ln 'return null;.*//\|return null;.*TODO\|return null;.*FIXME\|// placeholder\|// NotImplemented\|throw new UnsupportedOperationException' 2>/dev/null || true)
  if [ -n "$PLACEHOLDER" ]; then
    echo "❌ Placeholder 구현 감지:" >&2
    echo "$PLACEHOLDER" >&2
    exit 2
  fi
fi

echo -e '✅ DoD 검증 통과\n' >&2

# ============================================================================
# 작업 완료 시 ADR 및 리포트 작성 안내
# ============================================================================
if echo "$LAST_MSG" | grep -qiE '완료|complete|done|finished|머지|merged|닫음|closed|성공'; then
  echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' >&2
  echo '🎉 작업 완료 감지!' >&2
  echo '' >&2
  echo '📋 완료된 작업에 대해 다음을 확인하세요:' >&2
  echo '  1️⃣  ADR 문서 작성 (docs/adr/)' >&2
  echo '  2️⃣  진행 리포트 업데이트 (docs/05_Reports/)' >&2
  echo '' >&2
  echo '📝 리포트 템플릿 위치:' >&2
  echo '   - docs/05_Reports/module-migration-progress-report.md' >&2
  echo '   - docs/98_Templates/Chaos_Report_Template.md' >&2
  echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' >&2
  echo '' >&2
fi

# ============================================================================
# 세션 종료 시 리포트 생성
# ============================================================================
REPORT_DIR="$CWD/docs/05_Reports"
REPORT_FILE="$REPORT_DIR/session-report-$(date +%Y-%m-%d-%H%M%S).md"

if [ -d "$REPORT_DIR" ]; then
  echo -e '\n📝 세션 리포트 생성 중...' >&2

  # Git 상태 수집
  cd "$CWD"
  CURRENT_BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
  RECENT_COMMITS=$(git log --oneline -5 2>/dev/null || echo "N/A")
  CHANGED_FILES=$(git diff --name-only HEAD~5 2>/dev/null | head -20 || echo "N/A")

  # 리포트 작성
  cat > "$REPORT_FILE" << EOFREPORT
# 세션 리포트

**생성 일시**: $(date '+%Y-%m-%d %H:%M:%S')
**브랜치**: $CURRENT_BRANCH

## 최근 커밋

\`\`\`
$RECENT_COMMITS
\`\`\`

## 변경된 파일 (최근 5개 커밋)

\`\`\`
$CHANGED_FILES
\`\`\`

## 검증 결과

- ✅ PfastTest 통과
- ✅ DoD 검증 통과
- ✅ Stateless 설계 준수
- ✅ 미완성 코드 없음

---
*자동 생성됨 by stop-validation.sh*
EOFREPORT

  echo -e "✅ 리포트 생성 완료: $REPORT_FILE\n" >&2
fi

exit 0

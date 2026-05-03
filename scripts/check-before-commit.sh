#!/usr/bin/env bash
set -euo pipefail

echo "🔍 Running pre-commit checks..."

# --- Project root check ---
if [ ! -f "./gradlew" ]; then
  echo "❌ gradlew not found. Run this script from the project root."
  exit 1
fi

# --- Only run when staged files include relevant sources ---
STAGED=$(git diff --cached --name-only --diff-filter=ACMR 2>/dev/null || true)

if [ -n "$STAGED" ]; then
  RELEVANT=$(echo "$STAGED" | grep -E '\.(java|kt|kts)$|build\.gradle(\.kts)?$|settings\.gradle(\.kts)?$|gradle\.properties$' || true)
  if [ -z "$RELEVANT" ]; then
    echo "⏭️  No Java/Kotlin/Gradle files staged. Skipping checks."
    exit 0
  fi
  echo "   Staged relevant files:"
  echo "$RELEVANT" | sed 's/^/     /'
fi

# --- Run Gradle check (includes ktlintCheck, test, archunit) ---
echo ""
echo "🔧 Running ./gradlew check..."
if ./gradlew check --quiet 2>&1; then
  echo ""
  echo "✅ Gradle check passed."
else
  echo ""
  echo "❌ Pre-commit checks FAILED."
  echo "   Fix the issues above or commit with --no-verify to bypass."
  exit 1
fi

# --- Server Runtime Verification ---
echo ""
SERVER_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null || echo "000")
if [ "$SERVER_HEALTH" = "200" ]; then
  echo "🌐 Server detected. Running API runtime verification..."
  TEST_IGN="진격캐넌"
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/v5/characters/$(python3 -c "import urllib.parse; print(urllib.parse.quote('$TEST_IGN'))")/expectation" 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ]; then
    echo "   API response: $HTTP_CODE. Checking server logs..."
    sleep 3
    LOG_FILE="module-app/logs/app.log"
    if [ -f "$LOG_FILE" ]; then
      RECENT_ERRORS=$(tail -50 "$LOG_FILE" | grep -c "ERROR" || echo "0")
      RECENT_SUCCESS=$(tail -50 "$LOG_FILE" | grep -c "Calculation completed" || echo "0")
      if [ "$RECENT_ERRORS" -gt 0 ]; then
        echo "⚠️  Errors detected in server log ($RECENT_ERRORS ERROR entries):"
        tail -50 "$LOG_FILE" | grep "ERROR" | tail -5 | sed 's/^/     /'
        echo "   Review before proceeding."
      elif [ "$RECENT_SUCCESS" -gt 0 ]; then
        echo "✅ Server runtime verification passed. Calculation completed successfully."
      else
        echo "⚠️  No calculation completion log found yet. Pipeline may still be processing."
      fi
    fi
  else
    echo "⚠️  API returned HTTP $HTTP_CODE (expected 202). Check server logs."
  fi
else
  echo "⚠️  Server not running (health check: $SERVER_HEALTH)."
  echo "   Server runtime verification skipped."
  echo "   Run server manually and verify before pushing: ./gradlew :module-app:bootRun"
fi

echo ""
echo "✅ Pre-commit checks passed."

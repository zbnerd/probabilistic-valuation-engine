#!/usr/bin/env bash
# E2E Test: Like Toggle (ADR-029)
# Usage: ./scripts/e2e-like-toggle.sh [BASE_URL]
set -uo pipefail

# ── Load from .env (EUC-KR safe) ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: .env not found at $ENV_FILE"
    exit 1
fi

read_env() {
    grep -aP "^${1}=" "$ENV_FILE" | head -1 | cut -d'=' -f2-
}

APP_SERVER_IP=$(read_env "APP_SERVER_IP")
BASE_URL="${1:-http://${APP_SERVER_IP}:8080}"

USER1_IGN=$(read_env "USER1_IGN")
USER1_KEY=$(read_env "USER1_NEXON_API_KEY")

USER2_IGN=$(read_env "USER2_IGN")
USER2_KEY=$(read_env "USER2_NEXON_API_KEY")

USER3_IGN=$(read_env "USER3_IGN")
USER3_KEY=$(read_env "USER3_NEXON_API_KEY")

# ── Colors ──
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PASS=0
FAIL=0

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo -e "  ${GREEN}PASS${NC} $label"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${NC} $label: expected='$expected' actual='$actual'"
        ((FAIL++))
    fi
}

assert_contains() {
    local label="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo -e "  ${GREEN}PASS${NC} $label"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${NC} $label: '$needle' not found in response"
        ((FAIL++))
    fi
}

assert_status() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo -e "  ${GREEN}PASS${NC} $label"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${NC} $label: expected HTTP $expected, got $actual"
        ((FAIL++))
    fi
}

# ── Login helper ──
login() {
    local ign="$1" key="$2"
    curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"apiKey\":\"${key}\",\"userIgn\":\"${ign}\"}"
}

# ── Like toggle helper ──
toggle_like() {
    local token="$1" target_ign="$2"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/v4/characters/${target_ign}/like" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json")
    echo "$resp"
}

# ── Like status helper ──
like_status() {
    local token="$1" target_ign="$2"
    local resp
    resp=$(curl -s -w "\n%{http_code}" "${BASE_URL}/api/v4/characters/${target_ign}/like/status" \
        -H "Authorization: Bearer ${token}")
    echo "$resp"
}

# ── Extract JSON field (no jq dependency) ──
json_val() {
    echo "$1" | { grep -oP "\"$2\"\\s*:\\s*\\K[^,}]+" || true; } | tr -d '"' | sed 's/,$//' | head -1
}

json_bool() {
    echo "$1" | { grep -oP "\"$2\"\\s*:\\s*\\K[a-z]+" || true; } | head -1
}

json_num() {
    echo "$1" | { grep -oP "\"$2\"\\s*:\\s*\\K[0-9]+" || true; } | head -1
}

# ════════════════════════════════════════
echo -e "${CYAN}═══ E2E Test: Like Toggle (ADR-029) ═══${NC}"
echo "Target: ${BASE_URL}"
echo ""

# ── Step 0: Login all 3 users ──
echo -e "${YELLOW}[Step 0] Login${NC}"

RESP=$(login "$USER1_IGN" "$USER1_KEY")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "USER1 login" "200" "$STATUS"
TOKEN1=$(json_val "$BODY" "accessToken")
if [ -n "$TOKEN1" ]; then echo -e "  ${GREEN}PASS${NC} USER1 token acquired"; ((PASS++)); else echo -e "  ${RED}FAIL${NC} USER1 token missing"; ((FAIL++)); fi

RESP=$(login "$USER2_IGN" "$USER2_KEY")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "USER2 login" "200" "$STATUS"
TOKEN2=$(json_val "$BODY" "accessToken")
if [ -n "$TOKEN2" ]; then ((PASS++)); else echo -e "  ${RED}FAIL${NC} USER2 token missing"; ((FAIL++)); fi

RESP=$(login "$USER3_IGN" "$USER3_KEY")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "USER3 login" "200" "$STATUS"
TOKEN3=$(json_val "$BODY" "accessToken")
if [ -n "$TOKEN3" ]; then ((PASS++)); else echo -e "  ${RED}FAIL${NC} USER3 token missing"; ((FAIL++)); fi

if [ -z "$TOKEN1" ] || [ -z "$TOKEN2" ] || [ -z "$TOKEN3" ]; then
    echo -e "${RED}Login failed. Check credentials and server URL.${NC}"
    exit 1
fi
echo ""

# ── Step 0.5: Ensure clean state for Step 1 ──
RESP=$(like_status "$TOKEN1" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
if [ "$(json_bool "$BODY" "liked")" = "true" ]; then
    toggle_like "$TOKEN1" "$USER3_IGN" > /dev/null 2>&1
fi

# ── Step 1: Basic Toggle Flow ──
echo -e "${YELLOW}[Step 1] Basic Toggle Flow${NC}"

# 1-1: USER1 likes USER3 (긱장인)
RESP=$(toggle_like "$TOKEN1" "$USER3_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "1-1 First like" "200" "$STATUS"
assert_eq "1-1 result=LIKED" "true" "$(json_bool "$BODY" "liked")"

# 1-2: Toggle again → UNLIKE
RESP=$(toggle_like "$TOKEN1" "$USER3_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "1-2 Toggle unlike" "200" "$STATUS"
assert_eq "1-2 result=UNLIKED" "false" "$(json_bool "$BODY" "liked")"

# 1-3: Toggle again → LIKED
RESP=$(toggle_like "$TOKEN1" "$USER3_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "1-3 Re-like" "200" "$STATUS"
assert_eq "1-3 result=LIKED" "true" "$(json_bool "$BODY" "liked")"

# 1-4: Status check (liked)
RESP=$(like_status "$TOKEN1" "$USER3_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_status "1-4 Status (liked)" "200" "$STATUS"
assert_eq "1-4 liked=true" "true" "$(json_bool "$BODY" "liked")"
assert_eq "1-4 likeCount=1" "1" "$(json_num "$BODY" "likeCount")"

# 1-5: Unlike then status check
toggle_like "$TOKEN1" "$USER3_IGN" > /dev/null
RESP=$(like_status "$TOKEN1" "$USER3_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_eq "1-5 liked=false" "false" "$(json_bool "$BODY" "liked")"
assert_eq "1-5 likeCount=0" "0" "$(json_num "$BODY" "likeCount")"
echo ""

# ── Step 2: Self-Like Prevention ──
echo -e "${YELLOW}[Step 2] Self-Like Prevention${NC}"

RESP=$(toggle_like "$TOKEN1" "$USER1_IGN")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
assert_eq "2-1 Self-like blocked (status 4xx)" "1" "$( [ "$STATUS" -ge 400 ] && [ "$STATUS" -lt 500 ] && echo 1 || echo 0 )"
echo ""

# ── Step 3: Counter Consistency (Multi-user) ──
echo -e "${YELLOW}[Step 3] Counter Consistency${NC}"

# Clean slate: ensure USER3 (긱장인) has no likes from anyone
RESP=$(like_status "$TOKEN1" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
if [ "$(json_bool "$BODY" "liked")" = "true" ]; then
    toggle_like "$TOKEN1" "$USER3_IGN" > /dev/null 2>&1 || true
fi
RESP=$(like_status "$TOKEN2" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
if [ "$(json_bool "$BODY" "liked")" = "true" ]; then
    toggle_like "$TOKEN2" "$USER3_IGN" > /dev/null 2>&1 || true
fi

# Now: USER1 likes USER3
RESP=$(toggle_like "$TOKEN1" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_eq "3-1 USER1→USER3 count=1" "1" "$(json_num "$BODY" "likeCount")"

# USER2 likes USER3
RESP=$(toggle_like "$TOKEN2" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_eq "3-2 USER2→USER3 count=2" "2" "$(json_num "$BODY" "likeCount")"

# USER1 cancels
RESP=$(toggle_like "$TOKEN1" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_eq "3-3 USER1 cancel count=1" "1" "$(json_num "$BODY" "likeCount")"

# USER2 cancels
RESP=$(toggle_like "$TOKEN2" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_eq "3-4 USER2 cancel count=0" "0" "$(json_num "$BODY" "likeCount")"
echo ""

# ── Step 4: Non-existent IGN ──
echo -e "${YELLOW}[Step 4] Non-existent IGN${NC}"

RESP=$(toggle_like "$TOKEN1" "존재하지않는닉네임12345")
STATUS=$(echo "$RESP" | tail -1)
assert_eq "4-1 Non-existent IGN → 4xx" "1" "$( [ "$STATUS" -ge 400 ] && [ "$STATUS" -lt 500 ] && echo 1 || echo 0 )"
echo ""

# ── Step 5: Unauthenticated Request ──
echo -e "${YELLOW}[Step 5] Unauthenticated Request${NC}"

RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/v4/characters/${USER3_IGN}/like" \
    -H "Content-Type: application/json")
STATUS=$(echo "$RESP" | tail -1)
assert_eq "5-1 No auth → 401" "401" "$STATUS"
echo ""

# ── Step 6: Concurrent Toggle (Race Condition) ──
echo -e "${YELLOW}[Step 6] Concurrent Toggle (Race Condition)${NC}"

# Ensure clean state: USER1 has not liked USER3
toggle_like "$TOKEN1" "$USER3_IGN" > /dev/null 2>&1 || true

# Fire 5 concurrent LIKE requests from USER1 → USER3
for i in {1..5}; do
    curl -s -X POST "${BASE_URL}/api/v4/characters/${USER3_IGN}/like" \
        -H "Authorization: Bearer ${TOKEN1}" \
        -H "Content-Type: application/json" \
        > /dev/null &
done
wait

# Verify: count should be 1 (odd toggles = LIKED, but ON CONFLICT prevents duplicates)
RESP=$(like_status "$TOKEN1" "$USER3_IGN")
BODY=$(echo "$RESP" | head -n -1)
FINAL_COUNT=$(json_num "$BODY" "likeCount")
FINAL_LIKED=$(json_bool "$BODY" "liked")
echo "  Concurrent result: liked=$FINAL_LIKED, likeCount=$FINAL_COUNT"

# 5 concurrent toggles: 1st=LIKE, 2nd=UNLIKE, 3rd=LIKE, 4th=UNLIKE, 5th=LIKE
# But with race condition, some may collide → final state depends on ordering
# Key assertion: count should be 0 or 1 (not 2,3,4,5)
if [ "$FINAL_COUNT" -le 1 ]; then
    echo -e "  ${GREEN}PASS${NC} 6-1 Count safe under concurrency (count=$FINAL_COUNT)"
    ((PASS++))
else
    echo -e "  ${RED}FAIL${NC} 6-1 Count drift detected! count=$FINAL_COUNT"
    ((FAIL++))
fi

# Cleanup
toggle_like "$TOKEN1" "$USER3_IGN" > /dev/null 2>&1 || true
echo ""

# ── Step 7: Response DTO Schema ──
echo -e "${YELLOW}[Step 7] Response DTO Schema${NC}"

RESP=$(toggle_like "$TOKEN1" "$USER2_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_contains "7-1 Has targetUserIgn" "targetUserIgn" "$BODY"
assert_contains "7-2 Has liked" "liked" "$BODY"
assert_contains "7-3 Has likeCount" "likeCount" "$BODY"

RESP=$(like_status "$TOKEN1" "$USER2_IGN")
BODY=$(echo "$RESP" | head -n -1)
assert_contains "7-4 Status has targetUserIgn" "targetUserIgn" "$BODY"
assert_contains "7-5 Status has liked" "liked" "$BODY"
assert_contains "7-6 Status has likeCount" "likeCount" "$BODY"

# Cleanup
toggle_like "$TOKEN1" "$USER2_IGN" > /dev/null 2>&1 || true
echo ""

# ════════════════════════════════════════
echo -e "${CYAN}═══ Results ═══${NC}"
TOTAL=$((PASS + FAIL))
echo -e "  ${GREEN}PASS: ${PASS}/${TOTAL}${NC}"
if [ "$FAIL" -gt 0 ]; then
    echo -e "  ${RED}FAIL: ${FAIL}/${TOTAL}${NC}"
    exit 1
else
    echo -e "  ${GREEN}All tests passed!${NC}"
    exit 0
fi

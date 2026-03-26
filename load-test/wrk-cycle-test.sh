#!/bin/bash
# wrk Load Test - Cycle through all 300k user IGNS
# Usage: ./wrk-cycle-test.sh

set -e

BASE_URL="http://localhost:8080"
LUA_SCRIPT="/home/maple/probabilistic-valuation-engine/load-test-scripts/wrk-cycle-all-users.lua"
DURATION="2m"  # 2 minutes = ~36,000 requests at 300 RPS
THREADS=10
CONNECTIONS=300

echo "========================================================================"
echo "🔥 WRK LOAD TEST - Cycle Through All 300k User IGNS"
echo "========================================================================"
echo "Target: 300 RPS for 2 minutes"
echo "Expected: ~36,000 requests, covering ~12% of 300k users"
echo "Started: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# Run wrk
wrk \
  -t "$THREADS" \
  -c "$CONNECTIONS" \
  -d "$DURATION" \
  -s "$LUA_SCRIPT" \
  "$BASE_URL/api/v4/characters/test/expectation"

echo ""
echo "✅ Load test completed: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

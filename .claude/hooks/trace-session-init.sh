#!/usr/bin/env bash
# trace-session-init.sh — SessionStart hook: initializes session marker
set -euo pipefail

TRACE_BASE="${CLAUDE_PROJECT_DIR:-$(pwd)}/docs/ai-traces"
SESSION_MARKER="$TRACE_BASE/.current-session"

# Create new session marker
SESSION_ID="$(date +%Y%m%d/%Y%m%d-%H%M%S)-$$"
mkdir -p "$(dirname "$SESSION_MARKER")"
echo "$SESSION_ID" > "$SESSION_MARKER"

# Create session directory
SESSION_DIR="${TRACE_BASE}/${SESSION_ID}"
mkdir -p "$SESSION_DIR"

# Log session start
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ENTRY=$(jq -n \
    --arg ts "$TS" \
    --arg session_id "$SESSION_ID" \
    '{timestamp: $ts, event: "session_start", session_id: $session_id}')

echo "$ENTRY" >> "$SESSION_DIR/session.jsonl"
echo "AI trace session: $SESSION_ID"

# Compress trace files older than 7 days
find "$TRACE_BASE" -name "*.jsonl" -mtime +7 ! -name "*.gz" -exec gzip {} \; 2>/dev/null || true

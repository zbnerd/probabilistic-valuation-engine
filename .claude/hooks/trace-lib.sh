#!/usr/bin/env bash
# trace-lib.sh — Shared functions for AI trace logging
set -euo pipefail

TRACE_BASE="${CLAUDE_PROJECT_DIR:-$(pwd)}/docs/ai-traces"
SESSION_MARKER="$TRACE_BASE/.current-session"

# Get or create stable session ID
_ensure_session() {
    if [ -f "$SESSION_MARKER" ]; then
        # Existing session — reuse
        cat "$SESSION_MARKER"
    else
        # New session
        local sid
        sid="$(date +%Y%m%d/%Y%m%d-%H%M%S)-$$"
        echo "$sid" > "$SESSION_MARKER"
        echo "$sid"
    fi
}

TRACE_SESSION_ID="$(_ensure_session)"
TRACE_DIR="${TRACE_BASE}/${TRACE_SESSION_ID}"

# Ensure trace directory exists
mkdir -p "$TRACE_DIR"

# Append JSONL entry to a trace file
# Usage: trace_append "filename.jsonl" '{"key":"value"}'
trace_append() {
    local file="$TRACE_DIR/$1"
    local entry="$2"
    echo "$entry" >> "$file"
}

# Current ISO timestamp
trace_ts() {
    date -u +%Y-%m-%dT%H:%M:%SZ
}

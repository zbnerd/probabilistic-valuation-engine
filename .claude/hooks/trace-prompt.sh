#!/usr/bin/env bash
# trace-prompt.sh — UserPromptSubmit hook: logs user prompts
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/trace-lib.sh"

INPUT=$(cat)

PROMPT=$(echo "$INPUT" | jq -r '.prompt // ""' | head -c 2000)
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // ""')
TS=$(trace_ts)

ENTRY=$(jq -n \
    --arg ts "$TS" \
    --arg prompt "$PROMPT" \
    --arg session_id "$SESSION_ID" \
    '{timestamp: $ts, role: "user", content: $prompt, session_id: $session_id}')

trace_append "prompts.jsonl" "$ENTRY"

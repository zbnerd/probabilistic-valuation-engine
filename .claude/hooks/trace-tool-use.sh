#!/usr/bin/env bash
# trace-tool-use.sh — PostToolUse hook: logs all tool invocations to JSONL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/trace-lib.sh"

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
TOOL_INPUT=$(echo "$INPUT" | jq -c '.tool_input // {}')
RESULT=$(echo "$INPUT" | jq -r '.tool_result // ""' | head -c 500)
ERROR=$(echo "$INPUT" | jq -r '.error // null')
TS=$(trace_ts)

# Build JSONL entry
ENTRY=$(jq -n \
    --arg ts "$TS" \
    --arg tool "$TOOL_NAME" \
    --argjson input "$TOOL_INPUT" \
    --arg result "$RESULT" \
    --arg error "$ERROR" \
    '{timestamp: $ts, tool: $tool, input: $input, result_preview: $result, error: $error}')

# Extract file path if present (for Read/Edit/Write tools)
FILE_PATH=$(echo "$TOOL_INPUT" | jq -r '.file_path // .path // ""')
if [ -n "$FILE_PATH" ] && [ "$FILE_PATH" != "null" ]; then
    ENTRY=$(echo "$ENTRY" | jq --arg fp "$FILE_PATH" '. + {file: $fp}')
fi

trace_append "tool-use.jsonl" "$ENTRY"

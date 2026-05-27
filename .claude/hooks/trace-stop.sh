#!/usr/bin/env bash
# trace-stop.sh — Stop hook: generates session summary + git diff
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/trace-lib.sh"

TS=$(trace_ts)

# Record git diff if available
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git diff --stat HEAD~5 2>/dev/null > "$TRACE_DIR/git-diff.patch" || true
    git log --oneline -10 > "$TRACE_DIR/git-log.txt" || true
fi

# Count trace entries
TOOL_COUNT=$(wc -l < "$TRACE_DIR/tool-use.jsonl" 2>/dev/null || echo 0)
PROMPT_COUNT=$(wc -l < "$TRACE_DIR/prompts.jsonl" 2>/dev/null || echo 0)

# Tool frequency
TOOLS_USED=""
if [ -f "$TRACE_DIR/tool-use.jsonl" ]; then
    TOOLS_USED=$(jq -r '.tool' "$TRACE_DIR/tool-use.jsonl" | sort | uniq -c | sort -rn | head -10)
fi

# Generate summary
cat > "$TRACE_DIR/summary.md" << SUMMARY
# AI Session Summary

**Session:** ${TRACE_SESSION_ID}
**Date:** $(date +%Y-%m-%d)
**Ended:** ${TS}

## Stats
- Prompts: ${PROMPT_COUNT}
- Tool calls: ${TOOL_COUNT}

## Tool Usage
\`\`\`
${TOOLS_USED}
\`\`\`

## Files Modified
\`\`\`
$(git diff --name-only HEAD~5 2>/dev/null || echo "N/A")
\`\`\`

## Recent Commits
\`\`\`
$(git log --oneline -5 2>/dev/null || echo "N/A")
\`\`\`
SUMMARY

echo "Trace summary written to $TRACE_DIR/summary.md"

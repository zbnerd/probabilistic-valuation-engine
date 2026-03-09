#!/usr/bin/env bash
# pre-tool-use.sh - Security Core + MCP Enforcement
# Blocks dangerous commands and Enforces MCP usage for coding tools

set -euo pipefail

# Read JSON input from stdin
INPUT=$(cat)

# Extract tool info
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // ""')
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // ""')
RULES_FILE=".claude/rules/repo-protection.rules"

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# ========================================
# 1. SECURITY CHECK
# ========================================
if [ -f "$RULES_FILE" ]; then
    while read -r rule; do
        [ -z "$rule" ] && continue
        if echo "$COMMAND" | grep -E "$rule" > /dev/null 2>&1; then
            echo "❌ Blocked dangerous command: $rule"
            exit 1
        fi
    done < "$RULES_FILE"
fi

# Git repo check
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "❌ Not inside git repository"
    exit 1
fi

# ========================================
# 2. MCP ENFORCEMENT (for coding tools only)
# ========================================
if [[ "$TOOL_NAME" == "Write" || "$TOOL_NAME" == "Edit" ]]; then
    # Call enforce-mcp.sh
    "$PROJECT_ROOT/.claude/hooks/enforce-mcp.sh" "$TOOL_NAME"
    if [ $? -ne 0 ]; then
        echo "❌ MCP enforcement failed"
        exit 1
    fi
fi
echo "✅ Command allowed"
exit 0

#!/usr/bin/env bash
# stop-validation.sh - Final Validation Hook
# Runs tests, security scan, and checks for uncommitted changes

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"

echo "[Claude] Final validation..."

# Run pipelines
"$SCRIPT_DIR/../pipelines/test.sh"
"$SCRIPT_DIR/../pipelines/security.sh"

# CI parity check
if [ -d "$PROJECT_ROOT/.github/workflows" ]; then
    echo "⚠ Ensure local checks match CI"
fi
if ! git diff --quiet; then
    echo "⚠ Uncommitted changes detected"
fi
echo "✅ Validation finished"

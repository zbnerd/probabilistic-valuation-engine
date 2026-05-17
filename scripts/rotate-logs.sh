#!/usr/bin/env bash
# Rotate large log files by truncating to the last N megabytes.
# Usage: ./scripts/rotate-logs.sh [max-mb]
# Default: 100MB

set -euo pipefail

MAX_MB="${1:-100}"
MAX_BYTES=$((MAX_MB * 1024 * 1024))

# Log files to rotate (add new paths as needed)
LOG_FILES=(
  "/tmp/synchronizer.log"
  "/tmp/external-api.log"
  "/tmp/calculator.log"
  "/tmp/module-app.log"
)

for log_file in "${LOG_FILES[@]}"; do
  if [ ! -f "$log_file" ]; then
    continue
  fi

  size=$(stat -c%s "$log_file" 2>/dev/null || echo 0)

  if [ "$size" -gt "$MAX_BYTES" ]; then
    echo "Rotating $log_file ($(numfmt --to=iec "$size") > ${MAX_MB}MB)"
    tmp_file="${log_file}.tmp"
    tail -c "$MAX_BYTES" "$log_file" > "$tmp_file"
    mv "$tmp_file" "$log_file"
    echo "  -> truncated to last ${MAX_MB}MB"
  fi
done

echo "Log rotation complete"

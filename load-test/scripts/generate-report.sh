#!/usr/bin/env bash
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(dirname "${BASH_SOURCE[0]}")"
TEMPLATE_FILE="${SCRIPT_DIR}/../../docs/05_Reports/load-test/report-template.md"
REPORTS_DIR="${SCRIPT_DIR}/../../docs/05_Reports/load-test"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

usage() {
    echo "Usage: $0 <before.json> <after.json>"
    echo "  before.json  Path to baseline K6 JSON output"
    echo "  after.json   Path to new K6 JSON output"
    exit 1
}

# Check arguments
if [ $# -lt 2 ]; then
    usage
fi

BEFORE_FILE="$1"
AFTER_FILE="$2"

# Validate files exist
if [ ! -f "${BEFORE_FILE}" ]; then
    log_error "Before file not found: ${BEFORE_FILE}"
    exit 1
fi

if [ ! -f "${AFTER_FILE}" ]; then
    log_error "After file not found: ${AFTER_FILE}"
    exit 1
fi

log_info "Generating comparison report..."
echo "  Before: ${BEFORE_FILE}"
echo "  After:  ${AFTER_FILE}"
echo ""

# Function to extract metric from K6 JSON
extract_metric() {
    local file="$1"
    local metric="$2"
    local field="$3"
    
    # Use jq to extract specific metric values
    jq -r --arg m "$metric" --arg f "$field" \
        '.metrics[$m] | .[$f] // "N/A"' "$file" 2>/dev/null || echo "N/A"
}

# Function to format percentage change
format_delta() {
    local before="$1"
    local after="$2"
    
    # Handle N/A values
    if [[ "$before" == "N/A" || "$after" == "N/A" ]]; then
        echo "N/A"
        return
    fi
    
    # Parse as floats
    local before_val=$(echo "$before" | awk '{printf "%.2f", $1}')
    local after_val=$(echo "$after" | awk '{printf "%.2f", $1}')
    
    # Avoid division by zero
    if (( $(echo "$before_val == 0" | bc -l) )); then
        if (( $(echo "$after_val == 0" | bc -l) )); then
            echo "0%"
        else
            echo "∞"
        fi
        return
    fi
    
    local delta=$(echo "scale=2; (($after_val - $before_val) / $before_val) * 100" | bc)
    local sign=""
    
    if (( $(echo "$delta > 0" | bc -l) )); then
        sign="+"
    elif (( $(echo "$delta < 0" | bc -l) )); then
        sign=""
    fi
    
    echo "${sign}${delta}%"
}

# Function to colorize delta
colorize_delta() {
    local delta="$1"
    local lower_is_better="${2:-true}"
    
    if [[ "$delta" == "N/A" || "$delta" == "∞" ]]; then
        echo "${YELLOW}${delta}${NC}"
    elif [[ "$delta" == *"-"* ]]; then
        # Negative delta
        if [ "$lower_is_better" = "true" ]; then
            echo "${GREEN}${delta}${NC}"
        else
            echo "${RED}${delta}${NC}"
        fi
    else
        # Positive delta
        if [ "$lower_is_better" = "true" ]; then
            echo "${RED}${delta}${NC}"
        else
            echo "${GREEN}${delta}${NC}"
        fi
    fi
}

# Extract metrics
log_info "Extracting metrics..."

# Latency metrics (lower is better)
P50_BEFORE=$(extract_metric "$BEFORE_FILE" "http_req_duration" "p(50)")
P50_AFTER=$(extract_metric "$AFTER_FILE" "http_req_duration" "p(50)")
P50_DELTA=$(format_delta "$P50_BEFORE" "$P50_AFTER")

P90_BEFORE=$(extract_metric "$BEFORE_FILE" "http_req_duration" "p(90)")
P90_AFTER=$(extract_metric "$AFTER_FILE" "http_req_duration" "p(90)")
P90_DELTA=$(format_delta "$P90_BEFORE" "$P90_AFTER")

P99_BEFORE=$(extract_metric "$BEFORE_FILE" "http_req_duration" "p(99)")
P99_AFTER=$(extract_metric "$AFTER_FILE" "http_req_duration" "p(99)")
P99_DELTA=$(format_delta "$P99_BEFORE" "$P99_AFTER")

# Throughput metrics (higher is better)
RPS_BEFORE=$(extract_metric "$BEFORE_FILE" "http_reqs" "count")
RPS_AFTER=$(extract_metric "$AFTER_FILE" "http_reqs" "count")

# Calculate duration
DURATION_BEFORE=$(extract_metric "$BEFORE_FILE" "test_duration" "value")
DURATION_AFTER=$(extract_metric "$AFTER_FILE" "test_duration" "value")

# Calculate actual RPS
if [[ "$DURATION_BEFORE" != "N/A" && "$RPS_BEFORE" != "N/A" ]]; then
    RPS_BEFORE=$(echo "scale=2; $RPS_BEFORE / $DURATION_BEFORE" | bc)
fi
if [[ "$DURATION_AFTER" != "N/A" && "$RPS_AFTER" != "N/A" ]]; then
    RPS_AFTER=$(echo "scale=2; $RPS_AFTER / $DURATION_AFTER" | bc)
fi

RPS_DELTA=$(format_delta "$RPS_BEFORE" "$RPS_AFTER")

# Error rate (lower is better)
FAILED_BEFORE=$(extract_metric "$BEFORE_FILE" "http_req_failed" "passes")
TOTAL_BEFORE=$(extract_metric "$BEFORE_FILE" "http_reqs" "count")
FAILED_AFTER=$(extract_metric "$AFTER_FILE" "http_req_failed" "passes")
TOTAL_AFTER=$(extract_metric "$AFTER_FILE" "http_reqs" "count")

if [[ "$FAILED_BEFORE" != "N/A" && "$TOTAL_BEFORE" != "N/A" && "$TOTAL_BEFORE" != "0" ]]; then
    ERROR_BEFORE=$(echo "scale=4; ($FAILED_BEFORE / $TOTAL_BEFORE) * 100" | bc)
else
    ERROR_BEFORE="0"
fi

if [[ "$FAILED_AFTER" != "N/A" && "$TOTAL_AFTER" != "N/A" && "$TOTAL_AFTER" != "0" ]]; then
    ERROR_AFTER=$(echo "scale=4; ($FAILED_AFTER / $TOTAL_AFTER) * 100" | bc)
else
    ERROR_AFTER="0"
fi

ERROR_DELTA=$(format_delta "$ERROR_BEFORE" "$ERROR_AFTER")

# VUs
VUS_BEFORE=$(extract_metric "$BEFORE_FILE" "vus" "value")
VUS_AFTER=$(extract_metric "$AFTER_FILE" "vus" "value")
VUS_MAX_BEFORE=$(extract_metric "$BEFORE_FILE" "vus" "max")
VUS_MAX_AFTER=$(extract_metric "$AFTER_FILE" "vus" "max")

# Scenario name extraction from filename
SCENARIO=$(basename "$AFTER_FILE" | sed 's/_[0-9]*_[0-9]*\.json$//')

# Generate report
REPORT_FILE="${REPORTS_DIR}/${SCENARIO}_comparison_${TIMESTAMP}.md"

log_info "Generating report: ${REPORT_FILE}"

cat > "${REPORT_FILE}" << EOF
# Load Test Comparison Report: ${SCENARIO}

**Generated:** $(date -u +"%Y-%m-%d %H:%M:%S UTC")

## Executive Summary

This report compares the performance metrics between two load test runs:
- **Baseline:** $(basename "$BEFORE_FILE")
- **Current:** $(basename "$AFTER_FILE")

### Key Findings

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| P50 Latency | ${P50_BEFORE}ms | ${P50_AFTER}ms | $(colorize_delta "$P50_DELTA" "true") |
| P90 Latency | ${P90_BEFORE}ms | ${P90_AFTER}ms | $(colorize_delta "$P90_DELTA" "true") |
| P99 Latency | ${P99_BEFORE}ms | ${P99_AFTER}ms | $(colorize_delta "$P99_DELTA" "true") |
| Throughput | ${RPS_BEFORE} req/s | ${RPS_AFTER} req/s | $(colorize_delta "$RPS_DELTA" "false") |
| Error Rate | ${ERROR_BEFORE}% | ${ERROR_AFTER}% | $(colorize_delta "$ERROR_DELTA" "true") |

## Test Configuration

| Parameter | Before | After |
|-----------|--------|-------|
| Scenario | ${SCENARIO} | ${SCENARIO} |
| VUs | ${VUS_BEFORE} (max: ${VUS_MAX_BEFORE}) | ${VUS_AFTER} (max: ${VUS_MAX_AFTER}) |
| Duration | ${DURATION_BEFORE}s | ${DURATION_AFTER}s |

## Latency Distribution

### Response Time Percentiles

| Percentile | Before (ms) | After (ms) | Delta |
|------------|-------------|------------|-------|
| p50 | ${P50_BEFORE} | ${P50_AFTER} | $(colorize_delta "$P50_DELTA" "true") |
| p90 | ${P90_BEFORE} | ${P90_AFTER} | $(colorize_delta "$P90_DELTA" "true") |
| p95 | $(extract_metric "$BEFORE_FILE" "http_req_duration" "p(95)") | $(extract_metric "$AFTER_FILE" "http_req_duration" "p(95)") | $(colorize_delta "$(format_delta "$(extract_metric "$BEFORE_FILE" "http_req_duration" "p(95)")" "$(extract_metric "$AFTER_FILE" "http_req_duration" "p(95)")")" "true") |
| p99 | ${P99_BEFORE} | ${P99_AFTER} | $(colorize_delta "$P99_DELTA" "true") |

### Latency Trends

- **P50 Latency:** ${P50_BEFORE}ms → ${P50_AFTER}ms ($(colorize_delta "$P50_DELTA" "true"))
- **P90 Latency:** ${P90_BEFORE}ms → ${P90_AFTER}ms ($(colorize_delta "$P90_DELTA" "true"))
- **P99 Latency:** ${P99_BEFORE}ms → ${P99_AFTER}ms ($(colorize_delta "$P99_DELTA" "true"))

## Throughput Comparison

| Metric | Before | After | Delta |
|--------|--------|-------|-------|
| Total Requests | $(extract_metric "$BEFORE_FILE" "http_reqs" "count") | $(extract_metric "$AFTER_FILE" "http_reqs" "count") | $(colorize_delta "$(format_delta "$(extract_metric "$BEFORE_FILE" "http_reqs" "count")" "$(extract_metric "$AFTER_FILE" "http_reqs" "count")")" "false") |
| Requests/sec | ${RPS_BEFORE} | ${RPS_AFTER} | $(colorize_delta "$RPS_DELTA" "false") |

## Error Rate Analysis

| Metric | Before | After | Delta |
|--------|--------|-------|-------|
| Failed Requests | ${FAILED_BEFORE} | ${FAILED_AFTER} | $(colorize_delta "$(format_delta "$FAILED_BEFORE" "$FAILED_AFTER")" "true") |
| Error Rate | ${ERROR_BEFORE}% | ${ERROR_AFTER}% | $(colorize_delta "$ERROR_DELTA" "true") |

### Failed Request Breakdown

\`\`\`
# Before
$(extract_metric "$BEFORE_FILE" "http_req_failed" "passes" | sed 's/^/  /')

# After  
$(extract_metric "$AFTER_FILE" "http_req_failed" "passes" | sed 's/^/  /')
\`\`\`

## Cache Performance

> **Note:** Cache metrics require custom K6 metrics. Ensure your test exports \`cache_hits\` and \`cache_misses\` counters.

| Metric | Before | After | Delta |
|--------|--------|-------|-------|
| Cache Hits | N/A | N/A | N/A |
| Cache Misses | N/A | N/A | N/A |
| Hit Rate | N/A | N/A | N/A |

## Threshold Status

### Before

\`\`\`
$(extract_metric "$BEFORE_FILE" "thresholds" "passes" 2>/dev/null | sed 's/^/  /' || echo "No threshold data")
\`\`\`

### After

\`\`\`
$(extract_metric "$AFTER_FILE" "thresholds" "passes" 2>/dev/null | sed 's/^/  /' || echo "No threshold data")
\`\`\`

## Recommendations

EOF

# Add recommendations based on results
cat >> "${REPORT_FILE}" << EOF
EOF

# Performance regression check
if [[ "$P99_DELTA" == *"+"* ]]; then
    cat >> "${REPORT_FILE}" << EOF
- 🚨 **Performance Regression Detected:** P99 latency increased by ${P99_DELTA}
  - Investigate slow queries or resource contention
  - Review recent code changes for performance impact
  - Check database query plans and indexing

EOF
fi

# Error rate check
if [[ "$ERROR_DELTA" == *"+"* ]] && (( $(echo "$ERROR_AFTER > 0.1" | bc -l) )); then
    cat >> "${REPORT_FILE}" << EOF
- ⚠️ **Elevated Error Rate:** ${ERROR_AFTER}% (${ERROR_DELTA} increase)
  - Review application logs for error patterns
  - Check service health endpoints
  - Verify external dependencies

EOF
fi

# Throughput improvement
if [[ "$RPS_DELTA" == *"-"* ]]; then
    cat >> "${REPORT_FILE}" << EOF
- 📉 **Reduced Throughput:** Request rate decreased by ${RPS_DELTA}
  - Verify test conditions were equivalent
  - Check for resource constraints (CPU, memory, connections)
  - Review connection pool settings

EOF
fi

# Positive improvements
if [[ "$P99_DELTA" == *"-"* ]] && [[ "$ERROR_DELTA" != *"+"* ]]; then
    cat >> "${REPORT_FILE}" << EOF
- ✅ **Performance Improved:** P99 latency reduced by ${P99_DELTA} with stable error rate
  - Changes are having positive impact
  - Consider validating under higher load

EOF
fi

# Add metadata
cat >> "${REPORT_FILE}" << EOF
---

## Test Metadata

**Baseline File:** \`${BEFORE_FILE}\`
**Comparison File:** \`${AFTER_FILE}\`
**Report Generated:** $(date -u +"%Y-%m-%d %H:%M:%S UTC")

### K6 Version Information

\`\`\`bash
$(k6 version 2>/dev/null || echo "K6 not found in PATH")
\`\`\`

---

*This report was auto-generated by \`generate-report.sh\`*
EOF

log_info "Report generated successfully: ${REPORT_FILE}"
echo ""
echo "View the report:"
echo "  cat ${REPORT_FILE}"

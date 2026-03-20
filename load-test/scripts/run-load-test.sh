#!/usr/bin/env bash
set -euo pipefail

# Default values
SCENARIO="${1:-patch-day}"
DURATION="${2:-${DURATION:-2m}}"
VUS="${3:-${VUS:-100}}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULTS_DIR="$(dirname "${BASH_SOURCE[0]}")/../results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Health check function
health_check() {
    local max_attempts=30
    local attempt=1
    
    log_info "Performing health check on ${BASE_URL}..."
    
    while [ $attempt -le $max_attempts ]; do
        if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
            log_info "Health check passed!"
            return 0
        fi
        
        log_warn "Health check attempt ${attempt}/${max_attempts} failed. Retrying in 2 seconds..."
        sleep 2
        ((attempt++))
    done
    
    log_error "Health check failed after ${max_attempts} attempts"
    return 1
}

# Create results directory
mkdir -p "${RESULTS_DIR}"

# Display test configuration
log_info "Load Test Configuration:"
echo "  Scenario:    ${SCENARIO}"
echo "  Duration:    ${DURATION}"
echo "  VUs:         ${VUS}"
echo "  Base URL:    ${BASE_URL}"
echo "  Results:     ${RESULTS_DIR}/${SCENARIO}_${TIMESTAMP}.json"
echo ""

# Run health check
if ! health_check; then
    log_error "Service is not healthy. Exiting."
    exit 1
fi

# Run K6 test
log_info "Starting K6 load test..."

TEST_FILE="$(dirname "${BASH_SOURCE[0]}")/../scenarios/${SCENARIO}.js"
OUTPUT_FILE="${RESULTS_DIR}/${SCENARIO}_${TIMESTAMP}.json"

if [ ! -f "${TEST_FILE}" ]; then
    log_error "Test file not found: ${TEST_FILE}"
    exit 1
fi

# Run K6 with JSON output and threshold handling
if k6 run \
    --out json="${OUTPUT_FILE}" \
    --duration "${DURATION}" \
    --vus "${VUS}" \
    -e BASE_URL="${BASE_URL}" \
    "${TEST_FILE}"; then
    
    log_info "Load test completed successfully!"
    log_info "Results saved to: ${OUTPUT_FILE}"
    exit 0
else
    EXIT_CODE=$?
    
    # Check if thresholds failed
    if [ $EXIT_CODE -eq 105 ]; then
        log_error "Load test failed: Thresholds were not met"
    elif [ $EXIT_CODE -eq 108 ]; then
        log_error "Load test failed: Thresholds failed during teardown"
    else
        log_error "Load test failed with exit code: ${EXIT_CODE}"
    fi
    
    # Keep the output file even on failure for analysis
    if [ -f "${OUTPUT_FILE}" ]; then
        log_info "Partial results saved to: ${OUTPUT_FILE}"
    fi
    
    exit 1
fi

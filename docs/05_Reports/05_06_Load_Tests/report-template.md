# Load Test Report: {{SCENARIO}}

**Generated:** {{TIMESTAMP}}
**Environment:** {{ENVIRONMENT}}

## Executive Summary

### Test Overview

| Aspect | Details |
|--------|---------|
| Scenario | {{SCENARIO}} |
| Test Duration | {{DURATION}} |
| Virtual Users | {{VUS}} |
| Total Requests | {{TOTAL_REQUESTS}} |
| Base URL | {{BASE_URL}} |

### Key Results

| Metric | Target | Actual | Status |
|--------|--------|--------|--------|
| P50 Latency | < {{TARGET_P50}}ms | {{ACTUAL_P50}}ms | {{P50_STATUS}} |
| P90 Latency | < {{TARGET_P90}}ms | {{ACTUAL_P90}}ms | {{P90_STATUS}} |
| P99 Latency | < {{TARGET_P99}}ms | {{ACTUAL_P99}}ms | {{P99_STATUS}} |
| Error Rate | < {{TARGET_ERROR_RATE}}% | {{ACTUAL_ERROR_RATE}}% | {{ERROR_STATUS}} |
| Throughput | > {{TARGET_RPS}} req/s | {{ACTUAL_RPS}} req/s | {{RPS_STATUS}} |

### Summary

{{EXECUTIVE_SUMMARY_TEXT}}

## Test Configuration

### Load Parameters

| Parameter | Value |
|-----------|-------|
| Scenario | {{SCENARIO}} |
| VUs (Virtual Users) | {{VUS}} |
| Duration | {{DURATION}} |
| Ramp-up Time | {{RAMP_UP_TIME}} |
| Base URL | {{BASE_URL}} |
| Environment | {{ENVIRONMENT}} |

### Thresholds

| Metric | Threshold | Result |
|--------|-----------|--------|
| http_req_duration | < {{TARGET_P99}}ms (p99) | {{THRESHOLD_RESULT}} |
| http_req_failed | < {{TARGET_ERROR_RATE}}% | {{THRESHOLD_RESULT}} |

## Latency Distribution

### Response Time Percentiles

| Percentile | Value (ms) | Target (ms) | Status |
|------------|------------|-------------|--------|
| p50 | {{ACTUAL_P50}} | {{TARGET_P50}} | {{P50_STATUS}} |
| p75 | {{ACTUAL_P75}} | {{TARGET_P75}} | {{P75_STATUS}} |
| p90 | {{ACTUAL_P90}} | {{TARGET_P90}} | {{P90_STATUS}} |
| p95 | {{ACTUAL_P95}} | {{TARGET_P95}} | {{P95_STATUS}} |
| p99 | {{ACTUAL_P99}} | {{TARGET_P99}} | {{P99_STATUS}} |
| p99.9 | {{ACTUAL_P999}} | {{TARGET_P999}} | {{P999_STATUS}} |
| max | {{ACTUAL_MAX}} | {{TARGET_MAX}} | {{MAX_STATUS}} |

### Latency Distribution Chart

```
{{LATENCY_DISTRIBUTION_CHART}}
```

### Latency by Endpoint

| Endpoint | Method | Count | p50 | p90 | p99 |
|----------|--------|-------|-----|-----|-----|
{{ENDPOINT_LATENCY_TABLE}}

## Throughput Analysis

### Request Volume

| Metric | Value |
|--------|-------|
| Total Requests | {{TOTAL_REQUESTS}} |
| Requests/sec | {{ACTUAL_RPS}} |
| Requests/VU/sec | {{RPS_PER_VU}} |

### Throughput Over Time

```
{{THROUGHPUT_TIMELINE_CHART}}
```

### Endpoint Breakdown

| Endpoint | Method | Requests | % of Total |
|----------|--------|----------|------------|
{{ENDPOINT_REQUEST_TABLE}}

## Error Rate Analysis

### Error Summary

| Metric | Value | Rate |
|--------|-------|------|
| Total Requests | {{TOTAL_REQUESTS}} | 100% |
| Successful | {{SUCCESSFUL_REQUESTS}} | {{SUCCESS_RATE}}% |
| Failed | {{FAILED_REQUESTS}} | {{ACTUAL_ERROR_RATE}}% |

### Error Breakdown by Status Code

| Status Code | Count | Percentage | Description |
|-------------|-------|------------|-------------|
{{STATUS_CODE_TABLE}}

### Error Breakdown by Endpoint

| Endpoint | Method | Errors | Error Rate |
|----------|--------|--------|------------|
{{ENDPOINT_ERROR_TABLE}}

### Error Timeline

```
{{ERROR_TIMELINE_CHART}}
```

## Cache Performance

> **Note:** Cache metrics are exported as custom K6 metrics. Ensure your test script includes:
> ```javascript
> export let cacheHits = new Counter('cache_hits');
> export let cacheMisses = new Counter('cache_misses');
> ```

### Cache Statistics

| Metric | Value |
|--------|-------|
| Cache Hits | {{CACHE_HITS}} |
| Cache Misses | {{CACHE_MISSES}} |
| Total Cache Lookups | {{TOTAL_CACHE_LOOKUPS}} |
| Hit Rate | {{CACHE_HIT_RATE}}% |
| Miss Rate | {{CACHE_MISS_RATE}}% |

### Cache Performance Over Time

```
{{CACHE_PERFORMANCE_CHART}}
```

### Cache Performance by Endpoint

| Endpoint | Cache Hits | Cache Misses | Hit Rate |
|----------|------------|--------------|----------|
{{ENDPOINT_CACHE_TABLE}}

## Resource Utilization

### Server Metrics

| Metric | Average | Max |
|--------|---------|-----|
| CPU Usage | {{AVG_CPU}}% | {{MAX_CPU}}% |
| Memory Usage | {{AVG_MEMORY}}MB | {{MAX_MEMORY}}MB |
| DB Connections | {{AVG_DB_CONN}} | {{MAX_DB_CONN}} |
| Redis Connections | {{AVG_REDIS_CONN}} | {{MAX_REDIS_CONN}} |

### Resource Timeline

```
{{RESOURCE_UTILIZATION_CHART}}
```

## Comparison with Baseline

> This section is populated when comparing two test runs using `generate-report.sh`

### Metric Comparison

| Metric | Baseline | Current | Delta |
|--------|----------|---------|-------|
| P50 Latency | {{BASELINE_P50}}ms | {{CURRENT_P50}}ms | {{P50_DELTA}} |
| P90 Latency | {{BASELINE_P90}}ms | {{CURRENT_P90}}ms | {{P90_DELTA}} |
| P99 Latency | {{BASELINE_P99}}ms | {{CURRENT_P99}}ms | {{P99_DELTA}} |
| Throughput | {{BASELINE_RPS}} req/s | {{CURRENT_RPS}} req/s | {{RPS_DELTA}} |
| Error Rate | {{BASELINE_ERROR}}% | {{CURRENT_ERROR}}% | {{ERROR_DELTA}} |

### Performance Changes

{{PERFORMANCE_CHANGE_ANALYSIS}}

## Recommendations

### Critical Issues

{{CRITICAL_ISSUES}}

### Performance Improvements

{{PERFORMANCE_RECOMMENDATIONS}}

### Configuration Changes

{{CONFIG_RECOMMENDATIONS}}

### Follow-up Actions

{{FOLLOW_UP_ACTIONS}}

---

## Test Artifacts

| Artifact | Location |
|----------|----------|
| K6 JSON Output | `{{JSON_OUTPUT_PATH}}` |
| Test Script | `{{TEST_SCRIPT_PATH}}` |
| Full Logs | `{{LOG_PATH}}` |

---

## Metadata

**Test Run ID:** {{TEST_RUN_ID}}
**Executed By:** {{EXECUTED_BY}}
**Test Framework:** K6 {{K6_VERSION}}
**Report Template Version:** 1.0.0

---

*This report was auto-generated. For questions or issues, please refer to the load testing documentation.*

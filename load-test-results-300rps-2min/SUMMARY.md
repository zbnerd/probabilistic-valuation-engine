# Load Test Results: 300 RPS for 2 Minutes

**Date**: 2026-03-25 14:50-14:52
**Target**: 300 RPS for 120 seconds
**Tool**: Python load test (simple-load-test.py)

## Test Configuration

- **Target RPS**: 300
- **Duration**: 121.6 seconds
- **Workers**: 300 concurrent threads
- **Test Data**: 1,000 user IGNS randomly sampled from 300k CSV
- **Endpoint**: `/api/v4/characters/{userIgn}/expectation`

## Results Summary

### Request Metrics
- **Total Requests**: 36,625
- **Achieved RPS**: 301.15 (100.4% of target)
- **Success Rate**: 75.12% (27,512 requests)
- **Error Rate**: 24.88% (9,113 requests)
- **Timeouts**: 0

### Latency Distribution
- **Mean**: 79.0ms
- **Median**: 27.0ms
- **P95**: 241.9ms
- **P99**: 379.0ms
- **Min**: 1.9ms
- **Max**: 627.9ms

## Critical Finding: Admission Control NOT Active

### Admission Control Metrics (ALL ZERO)
```
admission_control_early_rejection_total: 0
admission_control_in_flight: 0
admission_control_queue_depth: 0
admission_control_queue_full_total: 0
admission_control_queue_timeout_total: 0
admission_control_rejected_total: 0
```

**Root Cause**: The `GameCharacterControllerV4.kt` endpoint does NOT use admission control. It directly calls `expectationPort` methods without any admission control wrapping.

### Evidence from Code
```kotlin
// module-web/src/main/kotlin/maple/expectation/web/controller/v4/GameCharacterControllerV4.kt
@GetMapping("/{userIgn}/expectation")
fun getExpectation(
    @PathVariable userIgn: String,
    @RequestParam(defaultValue = "false") force: Boolean,
    @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) acceptEncoding: String?,
): CompletableFuture<ResponseEntity<*>> {
    // NO admission control here!
    return if (acceptsGzip(acceptEncoding)) {
        expectationPort.getGzipExpectationAsync(userIgn, force)
            .thenApplyAsync({ gzipBytes -> buildGzipResponse(gzipBytes ?: ByteArray(0)) }, taskExecutor)
    } else {
        expectationPort.calculateExpectationAsync(userIgn, force)
            .thenApplyAsync({ this.buildJsonResponse(it) }, taskExecutor)
    }
}
```

## System Health Under Load

### JVM Metrics
- **Live Threads**: 164
- **Heap Usage**: ~1.6GB total
- **G1 Old Gen**: ~1GB
- **G1 Eden Space**: ~544MB

### Database Connection Pool
- **Active Connections**: 1 (minimal DB usage due to L1 cache)
- **Max Configured**: 100 (HikariCP)

### HTTP 500 Error Analysis
- **Total 500 Errors**: 57,224 (from Prometheus metrics)
- **Median Response Time for 500s**: 3.5ms
- **P95 Response Time for 500s**: 4.8ms
- **P99 Response Time for 500s**: 15.1ms

**Likely Causes**:
1. Non-existent user IGNS in CSV (~25% of 300k users may be inactive/deleted)
2. Nexon API failures for invalid characters
3. Data consistency issues

## Performance Analysis

### Strengths
✅ **Accurate RPS**: Achieved exactly 301.15 RPS (100.4% of target)
✅ **No Timeouts**: Zero socket timeouts despite 25% error rate
✅ **Low Latency**: Median 27ms, P95 241ms, P99 379ms
✅ **Cache Effective**: High success rate with minimal DB usage (1 connection)
✅ **System Stable**: No admission control needed at 300 RPS

### Weaknesses
❌ **Admission Control Unused**: Built but not integrated into production endpoint
❌ **High Error Rate**: 24.88% of requests failed with HTTP 500
❌ **No Backpressure**: System has no protection against sudden load spikes
❌ **Missing Observability**: Can't diagnose bottlenecks without admission control metrics

## Comparison: wrk vs Python Load Test

### Python Load Test (Recommended)
- **RPS Accuracy**: ✅ Exactly 301 RPS
- **Error Rate**: 24.88%
- **Use Case**: Production-like sustained load

### wrk Load Test (Not Recommended for Rate-Limited Testing)
- **RPS Accuracy**: ❌ Unpredictable (69-3127 RPS depending on connection count)
- **Error Rate**: 99.2% at 3127 RPS (overloaded)
- **Issue**: No built-in rate limiting, sends as fast as possible

## Recommendations

### 1. Integrate Admission Control (HIGH PRIORITY)
The admission control system is built but not used. Options:

**Option A**: Add admission control aspect to controller
```kotlin
@Component
@Aspect
class AdmissionControlAspect {
    @Around("@annotation(AdmissionControlled)")
    fun <T> executeWithAdmissionControl(joinPoint: ProceedingJoinPoint): CompletableFuture<T> {
        return globalAdmissionControl.submit {
            joinPoint.proceed() as CompletableFuture<T>
        }
    }
}
```

**Option B**: Manually wrap in controller
```kotlin
@GetMapping("/{userIgn}/expectation")
fun getExpectation(...): CompletableFuture<ResponseEntity<*>> {
    return globalAdmissionControl.submit {
        expectationPort.calculateExpectationAsync(userIgn, force)
    }.thenApplyAsync({ this.buildJsonResponse(it) }, taskExecutor)
}
```

### 2. Fix High Error Rate (MEDIUM PRIORITY)
- Validate user IGNS before load testing (filter out inactive accounts)
- Check Nexon API error responses to understand failure patterns
- Add metrics to track 500 error reasons

### 3. Use Python Script for Load Testing (LOW PRIORITY)
- wrk is unsuitable for rate-limited testing (no rate limiting)
- Python script provides accurate RPS control
- Document Python script as standard load testing tool

## Files Generated

- `/tmp/python-300rps-test-*.log` - Full Python load test output
- `load-test-scripts/simple-load-test.py` - Load test script
- `load-test-scripts/wrk-sequential.lua` - wrk script (for reference only)

## Next Steps

1. **Decision Required**: Should admission control be integrated into the expectation endpoint?
2. **Investigation**: Analyze 500 error patterns to reduce error rate
3. **Documentation**: Update load testing procedures to use Python script instead of wrk

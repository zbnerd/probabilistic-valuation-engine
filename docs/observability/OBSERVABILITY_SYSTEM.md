# Complete Observability System - Prometheus Metrics

## Overview

This document defines the COMPLETE metric system for identifying bottlenecks, detecting backpressure, and diagnosing performance issues using Prometheus metrics.

---

## 1. ADMISSION CONTROL METRICS

### Purpose
Detect backpressure and request throttling at system entry point.

### Metrics

#### `admission_control_in_flight` (Gauge)
**Description**: Number of requests currently executing (passed admission, in-progress)

**Interpretation**:
- `≈ max_in_flight` (e.g., 300): **NORMAL** - System at full capacity
- `< max_in_flight * 0.5`: **UNDERUTILIZED** - Can handle more load
- **Sudden drop**: System stuck or crashed

**Prometheus Query**:
```promql
admission_control_in_flight
```

---

#### `admission_control_queue_depth` (Gauge)
**Description**: Number of requests waiting in admission queue

**Interpretation**:
- `= 0`: **NORMAL** - No queue buildup
- `Increasing`: **BACKPRESSURE** - Incoming rate > processing rate
- `> max_queue_size * 0.8` (e.g., >1600): **WARNING** - Near capacity

**Growth Rate Analysis**:
```promql
deriv(admission_control_queue_depth[1m])
```
- Positive value: Queue growing (bad)
- Negative value: Queue shrinking (good)
- Near zero: Stable

---

#### `admission_control_rejected_total` (Counter)
**Description**: Total number of requests rejected by admission control

**Tags**:
- `reason`: `queue_full` | `timeout` | `cpu_high`

**Interpretation**:
- **Increasing**: **CAPACITY EXCEEDED** - Load > system capacity
- **Rate calculation**:
```promql
rate(admission_control_rejected_total[1m])
```
- **Rejection Rate**:
```promql
rate(admission_control_rejected_total[1m]) / rate(http_server_requests_total[1m])
```
- `> 5%`: **CRITICAL** - System overloaded

---

#### `admission_control_early_rejection_total` (Counter)
**Description**: Requests rejected early due to heavy load (queue near full + CPU high)

**Purpose**: P0 FIX #2 - Prevent timeout storms by rejecting BEFORE queue fills completely

**Interpretation**:
- **Increasing**: System proactively rejecting to prevent cascade failure
- **Healthy behavior**: Prevents queue saturation

---

#### `admission_control_queue_wait_time_seconds` (Timer)
**Description**: Time requests spend waiting in admission queue

**Percentiles**:
```promql
# P50 wait time
histogram_quantile(0.50, rate(admission_control_queue_wait_time_seconds_bucket[1m]))

# P95 wait time
histogram_quantile(0.95, rate(admission_control_queue_wait_time_seconds_bucket[1m]))

# P99 wait time
histogram_quantile(0.99, rate(admission_control_queue_wait_time_seconds_bucket[1m]))
```

**Interpretation**:
- `< 100ms`: **EXCELLENT**
- `100ms - 1s`: **ACCEPTABLE**
- `> 1s`: **WARNING** - Queue backup causing delays
- `> 10s`: **CRITICAL** - Requests timing out

---

## 2. EXECUTOR METRICS (ThreadPoolTaskExecutor)

### Purpose
Detect CPU saturation and thread pool bottlenecks.

### Metrics

#### `executor_active_threads` (Gauge)
**Description**: Number of threads actively executing tasks

**Tags**:
- `name`: `taskExecutor` (main async executor)

**Interpretation**:
- `≈ max_pool_size` (e.g., 400): **NORMAL** - Full utilization
- `< core_pool_size * 0.5`: **UNDERUTILIZED**
- **Saturation Ratio**:
```promql
executor_active_threads{name="taskExecutor"} / executor_pool_max_threads{name="taskExecutor"}
```
- `> 0.9`: **SATURATED** - Consider increasing pool size

---

#### `executor_queued_tasks` (Gauge)
**Description**: Number of tasks waiting in executor queue

**Interpretation**:
- `= 0`: **NORMAL** - No queue buildup
- `Increasing`: **BOTTLENECK** - Task submission > execution rate
- `> queue_capacity * 0.8` (e.g., >1600): **WARNING** - Near saturation

**Growth Rate**:
```promql
deriv(executor_queued_tasks{name="taskExecutor"}[1m])
```

---

#### `executor_rejected_total` (Counter)
**Description**: Tasks rejected due to queue full

**Interpretation**:
- **Any value > 0**: **CRITICAL** - Executor queue capacity exceeded
- **Check rate**:
```promql
rate(executor_rejected_total{name="taskExecutor"}[1m])
```

---

#### `executor_completed_tasks_total` (Counter)
**Description**: Total number of tasks completed

**Usage**:
```promql
# Throughput (tasks per second)
rate(executor_completed_tasks_total{name="taskExecutor"}[1m])

# Total completed
executor_completed_tasks_total{name="taskExecutor"}
```

---

## 3. ASYNC EXECUTION METRICS (CompletableFuture)

### Purpose
Ensure async tasks are completing and not stuck.

### Metrics

#### `async_tasks_started` (Counter)
**Description**: Number of async tasks submitted to executor

**Tags**:
- `stage`: `admission` (passed admission control)

#### `async_tasks_completed` (Counter)
**Description**: Number of async tasks completed successfully

**Tags**:
- `result`: `success`

#### `async_tasks_failed` (Counter)
**Description**: Number of async tasks failed

**Tags**:
- `result`: `error`
- `exception`: Exception type (e.g., `TimeoutException`, `NullPointerException`)

#### `async_tasks_timeout` (Counter)
**Description**: Number of async tasks that timed out

**Interpretation**:
```promql
# Task completion rate
rate(async_tasks_completed[1m])

# Task failure rate
rate(async_tasks_failed[1m])

# Timeout rate
rate(async_tasks_timeout[1m])

# Success rate (should be > 95%)
sum(rate(async_tasks_completed[1m])) /
(sum(rate(async_tasks_completed[1m])) + sum(rate(async_tasks_failed[1m])) + sum(rate(async_tasks_timeout[1m])))
```

**Health Check**:
- `started ≈ completed + failed + timeout`: **HEALTHY** - All tasks accounted for
- `started > completed + failed + timeout`: **WARNING** - Tasks stuck in limbo
- `completed / started < 0.95`: **UNHEALTHY** - High failure rate

---

#### `async_task_duration_seconds` (Timer)
**Description**: End-to-end duration of async tasks

**Tags**:
- `result`: `success` | `error` | `timeout`
- `endpoint`: API endpoint

**Percentiles**:
```promql
# P50 latency (successful tasks)
histogram_quantile(0.50, rate(async_task_duration_seconds_bucket{result="success"}[1m]))

# P95 latency
histogram_quantile(0.95, rate(async_task_duration_seconds_bucket{result="success"}[1m]))

# P99 latency
histogram_quantile(0.99, rate(async_task_duration_seconds_bucket{result="success"}[1m]))
```

**Interpretation**:
- Compare P50/P95/P99 to identify outliers
- If P99 >> P95: **Long tail** - Some requests much slower
- Split by `result` tag to compare success vs failure latency

---

#### `async_task_queue_time_seconds` (Timer)
**Description**: Time tasks spend waiting in admission queue

**Purpose**: Separate queue wait from execution time

**Analysis**:
```promql
histogram_quantile(0.95, rate(async_task_queue_time_seconds_bucket[1m]))
```

---

#### `async_task_execution_time_seconds` (Timer)
**Description**: Actual computation time (excluding queue wait)

**Purpose**: Measure pure CPU work

**Analysis**:
```promql
histogram_quantile(0.95, rate(async_task_execution_time_seconds_bucket[1m]))
```

---

## 4. MICRO-BATCHING METRICS

### Purpose
Detect DB bottlenecks and batching efficiency.

### Metrics

#### `micro_batch_buffer_size` (Gauge)
**Description**: Current number of items in batch buffer

**Interpretation**:
- `= 0`: **NORMAL** - Buffer empty
- `Increasing`: **FLUSH PROBLEM** - Not flushing fast enough
- `> MAX_BUFFER_SIZE * 0.8`: **WARNING** - Near limit (5000 max)

---

#### `micro_batch_flush` (Counter)
**Description**: Number of batch flush operations executed

**Throughput**:
```promql
rate(micro_batch_flush[1m])
```

**Interpretation**:
- **Higher rate**: Frequent small flushes
- **Lower rate**: Infrequent large flushes

---

#### `micro_batch_flush_trigger` (Counter)
**Description**: Flush trigger events

**Tags**:
- `trigger`: `size` | `time` | `manual` | `buffer_limit`

**Analysis**:
```promql
# Flush breakdown by trigger
sum(rate(micro_batch_flush_trigger[1m])) by (trigger)
```

**Interpretation**:
- `size` trigger: Normal operation
- `time` trigger: Low traffic
- `buffer_limit` trigger: **WARNING** - Buffer filling too fast
- `manual` trigger: Manual intervention

---

#### `micro_batch_flush_size` (DistributionSummary)
**Description**: Distribution of batch sizes at flush time

**Stats**:
```promql
# Average batch size
avg(micro_batch_flush_size)

# P95 batch size
histogram_quantile(0.95, rate(micro_batch_flush_size_bucket[1m]))

# Max batch size
max(micro_batch_flush_size)
```

**Interpretation**:
- **Small batches** (< 100): Inefficient DB writes
- **Large batches** (> 1000): Risky (long transactions)
- **Optimal**: 500-1000 (configured flush size)

---

#### `micro_batch_flush_duration_seconds` (Timer)
**Description**: Time taken to flush batch to database

**Analysis**:
```promql
# P95 flush duration
histogram_quantile(0.95, rate(micro_batch_flush_duration_seconds_bucket[1m]))
```

**Interpretation**:
- `< 100ms`: **EXCELLENT**
- `100ms - 1s`: **ACCEPTABLE**
- `> 1s`: **WARNING** - DB bottleneck
- `> 10s`: **CRITICAL** - DB severely overloaded

---

#### `micro_batch_dedupe` (Counter)
**Description**: Number of duplicate tasks deduplicated (latest-wins)

**Dedupe Rate**:
```promql
rate(micro_batch_dedupe[1m]) / rate(micro_batch_offer_total[1m])
```

**Interpretation**:
- `> 50%`: **EXCELLENT** - High deduplication (hot keys)
- `10% - 50%`: **GOOD**
- `< 10%`: **LOW** - Mostly unique keys

---

## 5. API LAYER METRICS

### Purpose
User-facing performance and error rate.

### Metrics

#### `http_server_requests_total` (Counter)
**Description**: Total HTTP requests received

**Tags**:
- `endpoint`: API path (e.g., `/api/v4/characters/{userIgn}/expectation`)
- `method`: HTTP method
- `status`: HTTP status code
- `result`: `success` | `client_error` | `server_error`

**RPS Calculation**:
```promql
# Total RPS
sum(rate(http_server_requests_total[1m]))

# RPS by endpoint
sum(rate(http_server_requests_total[1m])) by (endpoint)

# RPS by result
sum(rate(http_server_requests_total[1m])) by (result)
```

---

#### `http_server_requests` (Timer)
**Description**: HTTP request latency distribution

**Tags**: Same as above

**Latency Percentiles**:
```promql
# P50 latency
histogram_quantile(0.50, rate(http_server_requests_bucket[1m]))

# P95 latency
histogram_quantile(0.95, rate(http_server_requests_bucket[1m]))

# P99 latency
histogram_quantile(0.99, rate(http_server_requests_bucket[1m]))
```

**By Status**:
```promql
# Successful request latency
histogram_quantile(0.95, rate(http_server_requests_bucket{result="success"}[1m]))

# Failed request latency
histogram_quantile(0.95, rate(http_server_requests_bucket{result="server_error"}[1m]))
```

---

#### `http_server_errors` (Counter)
**Description**: HTTP error responses (4xx, 5xx)

**Tags**:
- `endpoint`
- `method`
- `exception`: Exception type

**Error Rate**:
```promql
# Total error rate
sum(rate(http_server_errors[1m])) / sum(rate(http_server_requests_total[1m]))

# Error rate by endpoint
sum(rate(http_server_errors[1m])) by (endpoint) /
sum(rate(http_server_requests_total[1m])) by (endpoint)
```

**Interpretation**:
- `< 1%`: **EXCELLENT**
- `1% - 5%`: **ACCEPTABLE`
- `5% - 10%`: **WARNING**
- `> 10%`: **CRITICAL**

---

#### `http_server_timeouts` (Counter)
**Description**: Request timeouts (30s limit)

**Interpretation**:
- **Increasing**: System too slow or overloaded
- **Check correlation**:
  - High `admission_control_queue_depth` → Queue bottleneck
  - High `async_task_timeout` → Execution timeout

---

## 6. SYSTEM METRICS (JVM)

### Purpose
System-level resource utilization.

### Metrics

#### `process_cpu_usage` (Gauge)
**Description**: CPU usage of the JVM process

**Interpretation**:
- `< 50%`: **UNDERUTILIZED**
- `50% - 80%`: **HEALTHY**
- `> 80%`: **WARNING** - Near CPU saturation
- `≈ 100%`: **CRITICAL** - CPU bottleneck

**Note**: On multi-core systems, `process_cpu_usage` ranges from 0 to 1 (0% to 100%).

---

#### `jvm_memory_used_bytes` (Gauge)
**Description:** JVM heap memory usage

**Tags**:
- `area`: `heap`
- `id`: `G1 Old Gen` | `G1 Eden Space` | `G1 Survivor Space`

**Heap Usage**:
```promql
# Total heap used
jvm_memory_used_bytes{area="heap"}

# Heap by region
jvm_memory_used_bytes{area="heap",id="G1 Old Gen"}
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"}
```

**Interpretation**:
- **Old Gen filling up**: Memory leak or insufficient heap
- **Frequent GC cycles**: Check GC metrics

---

#### `jvm_gc_pause_seconds` (Timer)
**Description**: GC pause duration

**Analysis**:
```promql
# Total GC time rate
rate(jvm_gc_pause_seconds_sum[1m])

# GC pause count rate
rate(jvm_gc_pause_seconds_count[1m])

# P95 GC pause
histogram_quantile(0.95, rate(jvm_gc_pause_seconds_bucket[1m]))
```

**Interpretation**:
- `< 10ms**: **EXCELLENT**
- `10ms - 50ms`: **ACCEPTABLE**
- `> 50ms`: **WARNING** - Long GC pauses
- `> 500ms`: **CRITICAL** - Severe GC impact

**GC Overhead**:
```promql
# Percentage of time spent in GC
rate(jvm_gc_pause_seconds_sum[5m]) * 100
```
- `< 5%`: **HEALTHY**
- `5% - 10%`: **WARNING**
- `> 10%`: **CRITICAL**

---

## 7. BOTTLENECK DETECTION QUERIES

### Saturation Detection

```promql
# Admission control saturated
admission_control_in_flight / 300  # Should be < 0.9

# Executor saturated
executor_active_threads{name="taskExecutor"} / executor_pool_max_threads{name="taskExecutor"}  # Should be < 0.9

# Queue backup
(admission_control_queue_depth + executor_queued_tasks{name="taskExecutor"}) / (2000 + 2000)  # Combined queue utilization
```

### Rejection Rate

```promql
# Total rejection rate
sum(rate(admission_control_rejected_total[1m])) /
sum(rate(http_server_requests_total[1m]))

# Rejection by reason
sum(rate(admission_control_rejected_total[1m])) by (reason) /
sum(rate(http_server_requests_total[1m]))
```

### Error Rate

```promql
# Total error rate
sum(rate(http_server_errors[1m])) /
sum(rate(http_server_requests_total[1m]))

# Error rate by endpoint
sum(rate(http_server_errors[1m])) by (endpoint) /
sum(rate(http_server_requests_total[1m])) by (endpoint)
```

### Throughput Analysis

```promql
# Request throughput (RPS)
sum(rate(http_server_requests_total[1m]))

# Task completion throughput
rate(executor_completed_tasks_total{name="taskExecutor"}[1m])

# Batch flush throughput
rate(micro_batch_flush[1m])
```

### Latency Breakdown

```promql
# Queue wait time
histogram_quantile(0.95, rate(async_task_queue_time_seconds_bucket[1m]))

# Execution time
histogram_quantile(0.95, rate(async_task_execution_time_seconds_bucket[1m]))

# End-to-end latency
histogram_quantile(0.95, rate(http_server_requests_bucket[1m]))
```

---

## 8. ALERTING RULES (Recommended)

### Critical Alerts

```yaml
# High rejection rate
- alert: HighRejectionRate
  expr: |
    sum(rate(admission_control_rejected_total[5m])) /
    sum(rate(http_server_requests_total[5m])) > 0.1
  for: 2m
  annotations:
    summary: "Rejection rate > 10% for 2 minutes"

# Queue near capacity
- alert: QueueNearCapacity
  expr: |
    admission_control_queue_depth > 1600
  for: 1m
  annotations:
    summary: "Admission queue > 80% capacity"

# High error rate
- alert: HighErrorRate
  expr: |
    sum(rate(http_server_errors[5m])) /
    sum(rate(http_server_requests_total[5m])) > 0.1
  for: 2m
  annotations:
    summary: "Error rate > 10% for 2 minutes"

# Executor saturated
- alert: ExecutorSaturated
  expr: |
    executor_active_threads{name="taskExecutor"} /
    executor_pool_max_threads{name="taskExecutor"} > 0.9
  for: 5m
  annotations:
    summary: "Executor > 90% saturated for 5 minutes"

# High GC time
- alert: HighGCTime
  expr: |
    rate(jvm_gc_pause_seconds_sum[5m]) > 0.1
  for: 2m
  annotations:
    summary: "GC overhead > 10%"

# Batch flush stuck
- alert: BatchFlushStuck
  expr: |
    rate(micro_batch_flush[5m]) == 0
  for: 2m
  annotations:
    summary: "No batch flushes for 2 minutes"
```

### Warning Alerts

```yaml
# Increasing queue depth
- alert: QueueIncreasing
  expr: |
    deriv(admission_control_queue_depth[2m]) > 100
  for: 1m
  annotations:
    summary: "Queue growing at > 100 requests/minute"

# Latency degradation
- alert: HighLatency
  expr: |
    histogram_quantile(0.95, rate(http_server_requests_bucket[5m])) > 2
  for: 2m
  annotations:
    summary: "P95 latency > 2 seconds"

# CPU saturation
- alert: HighCPU
  expr: |
    process_cpu_usage > 0.8
  for: 5m
  annotations:
    summary: "CPU usage > 80% for 5 minutes"
```

---

## 9. GRAFANA DASHBOARD STRUCTURE

### Row 1: Admission Control (System Entry)

**Panel 1: In-Flight Requests**
```promql
admission_control_in_flight
```
- Visualization: Gauge
- Max: 300

**Panel 2: Queue Depth**
```promql
admission_control_queue_depth
```
- Visualization: Graph
- Thresholds: 1600 (warning), 1800 (critical)

**Panel 3: Rejection Rate**
```promql
sum(rate(admission_control_rejected_total[1m])) /
sum(rate(http_server_requests_total[1m]))
```
- Visualization: Stat
- Unit: percent (0-100)

---

### Row 2: Executor (Thread Pool)

**Panel 4: Active Threads**
```promql
executor_active_threads{name="taskExecutor"}
```
- Visualization: Graph
- Max: 400

**Panel 5: Queue Size**
```promql
executor_queued_tasks{name="taskExecutor"}
```
- Visualization: Graph
- Thresholds: 1600 (warning), 1800 (critical)

**Panel 6: Thread Saturation**
```promql
executor_active_threads{name="taskExecutor"} /
executor_pool_max_threads{name="taskExecutor"}
```
- Visualization: Stat
- Unit: percent (0-100)

---

### Row 3: Async Tasks (CompletableFuture)

**Panel 7: Task Throughput**
```promql
sum(rate(async_tasks_started[1m])) as started,
sum(rate(async_tasks_completed[1m])) as completed,
sum(rate(async_tasks_failed[1m])) as failed
```
- Visualization: Graph (multiple series)

**Panel 8: Task Success Rate**
```promql
sum(rate(async_tasks_completed[1m])) /
(sum(rate(async_tasks_completed[1m])) + sum(rate(async_tasks_failed[1m])))
```
- Visualization: Stat
- Unit: percent

**Panel 9: Task Duration (P95)**
```promql
histogram_quantile(0.95, rate(async_task_duration_seconds_bucket{result="success"}[1m]))
```
- Visualization: Graph
- Unit: seconds

---

### Row 4: Latency Analysis

**Panel 10: Request Latency (P50, P95, P99)**
```promql
# P50
histogram_quantile(0.50, rate(http_server_requests_bucket[1m]))

# P95
histogram_quantile(0.95, rate(http_server_requests_bucket[1m]))

# P99
histogram_quantile(0.99, rate(http_server_requests_bucket[1m]))
```
- Visualization: Graph (multiple series)

**Panel 11: Latency Heatmap**
```promql
rate(http_server_requests_bucket[1m])
```
- Visualization: Heatmap
- X-axis: Time
- Y-axis: Latency buckets

---

### Row 5: Errors & Timeouts

**Panel 12: Error Rate**
```promql
sum(rate(http_server_errors[1m])) /
sum(rate(http_server_requests_total[1m]))
```
- Visualization: Graph
- Unit: percent

**Panel 13: Error Breakdown**
```promql
sum(rate(http_server_errors[1m])) by (exception)
```
- Visualization: Pie chart

**Panel 14: Timeout Rate**
```promql
sum(rate(http_server_timeouts[1m]))
```
- Visualization: Graph

---

### Row 6: Micro-Batching (Write Path)

**Panel 15: Buffer Size**
```promql
micro_batch_buffer_size
```
- Visualization: Graph
- Threshold: 4000 (warning)

**Panel 16: Flush Rate**
```promql
rate(micro_batch_flush[1m])
```
- Visualization: Graph

**Panel 17: Flush Duration (P95)**
```promql
histogram_quantile(0.95, rate(micro_batch_flush_duration_seconds_bucket[1m]))
```
- Visualization: Graph
- Unit: seconds

**Panel 18: Batch Size Distribution**
```promql
histogram_quantile(0.95, rate(micro_batch_flush_size_bucket[1m]))
```
- Visualization: Graph
- Unit: tasks

---

### Row 7: System Resources

**Panel 19: CPU Usage**
```promql
process_cpu_usage * 100
```
- Visualization: Graph
- Unit: percent

**Panel 20: Heap Memory**
```promql
jvm_memory_used_bytes{area="heap",id="G1 Old Gen"}
```
- Visualization: Graph
- Unit: bytes

**Panel 21: GC Pause Time**
```promql
rate(jvm_gc_pause_seconds_sum[1m]) * 1000
```
- Visualization: Graph
- Unit: milliseconds

---

## 10. INTERPRETATION GUIDE

### Scenario 1: High Latency, Low CPU

**Symptoms**:
- P95 latency > 2s
- CPU usage < 50%

**Diagnosis**:
1. Check `admission_control_queue_depth`
   - **High**: Requests waiting in queue → Queue bottleneck
2. Check `async_task_queue_time_seconds`
   - **High**: Most time spent waiting (not executing)
3. Check `executor_active_threads`
   - **Low**: Underutilized thread pool

**Conclusion**: Architectural issue - requests queued but not executing fast enough

**Solution**:
- Increase executor thread pool size
- Check for blocking operations in async tasks
- Profile task execution to find slow code

---

### Scenario 2: High Latency, High CPU

**Symptoms**:
- P95 latency > 2s
- CPU usage > 80%

**Diagnosis**:
1. Check `async_task_execution_time_seconds`
   - **High**: Actual computation is slow
2. Check `executor_active_threads`
   - **≈ max**: All threads busy
3. Check GC metrics
   - **High GC overhead**: Memory pressure

**Conclusion**: CPU bottleneck - tasks are computationally expensive

**Solution**:
- Optimize expensive operations (e.g., calculations, database queries)
- Add caching for hot data
- Scale horizontally (add more instances)

---

### Scenario 3: Increasing Errors, Stable Latency

**Symptoms**:
- Error rate increasing
- P95 latency stable (< 1s)

**Diagnosis**:
1. Check `http_server_errors` by exception type
   - **Specific exception**: Application bug
2. Check `admission_control_rejected_total`
   - **Increasing**: System rejecting requests
3. Check `async_tasks_failed` by exception
   - **TimeoutException**: Tasks timing out
   - **Other**: Application errors

**Conclusion**: Application-level errors or capacity issues

**Solution**:
- Fix application bugs causing exceptions
- Increase timeout if tasks are legitimately slow
- Add capacity if rejection rate high

---

### Scenario 4: Increasing Queue Depth, Stable RPS

**Symptoms**:
- `admission_control_queue_depth` increasing
- RPS stable (~300)
- CPU stable

**Diagnosis**:
1. Check `async_tasks_completed` vs `async_tasks_started`
   - **started > completed**: Tasks not completing
2. Check `async_task_duration_seconds`
   - **Increasing**: Tasks getting slower
3. Check GC metrics
   - **High GC**: Memory leak or insufficient heap

**Conclusion**: Processing degradation - tasks completing slower over time

**Solution**:
- Check for memory leaks (profiling)
- Restart application if severe
- Optimize task logic to prevent slowdown
- Check database connection pool

---

### Scenario 5: Sudden Latency Spike

**Symptoms**:
- P99 latency suddenly jumps from 1s to 30s
- Other metrics stable before spike

**Diagnosis**:
1. Check `async_tasks_timeout`
   - **Increasing**: Tasks hitting 30s timeout
2. Check `admission_control_queue_timeout_total`
   - **Increasing**: Queue timeouts
3. Check GC pause times
   - **Spike**: Full GC freeze

**Conclusion**: **GC pause** or **database lock** causing cascade

**Solution**:
- Increase heap size
- Tune GC parameters
- Check for long-running database transactions
- Add database connection pool monitoring

---

### Scenario 6: High Rejection Rate, Normal Queues

**Symptoms**:
- Rejection rate > 10%
- Queue depth normal
- CPU normal

**Diagnosis**:
1. Check `admission_control_early_rejection_total`
   - **Increasing**: P0 FIX #2 active
2. Check `process_cpu_usage`
   - **High**: CPU load triggering early rejection
3. Check queue depth trend
   - **Increasing rapidly**: Early rejection preventing overload

**Conclusion**: System protecting itself from overload (HEALTHY behavior)

**Solution**:
- This is **working as designed** - early rejection prevents cascade failure
- Increase capacity if sustainable load requires it
- Tune early rejection thresholds if too aggressive

---

## 11. COMPLETE METRICS CHECKLIST

### ✅ Implemented

- [x] `admission_control_in_flight` (Gauge)
- [x] `admission_control_queue_depth` (Gauge)
- [x] `admission_control_rejected_total` (Counter)
- [x] `admission_control_queue_timeout_total` (Counter)
- [x] `admission_control_queue_full_total` (Counter)
- [x] `admission_control_early_rejection_total` (Counter)
- [x] `admission_control_queue_wait_time_seconds` (Timer)

- [x] `executor_active_threads` (Gauge)
- [x] `executor_queued_tasks` (Gauge)
- [x] `executor_pool_size_threads` (Gauge)
- [x] `executor_pool_core_threads` (Gauge)
- [x] `executor_pool_max_threads` (Gauge)
- [x] `executor_completed_tasks_total` (Counter)
- [x] `executor_rejected_total` (Counter)

- [x] `micro_batch_buffer_size` (Gauge)
- [x] `micro_batch_flush` (Counter)
- [x] `micro_batch_flush_size` (DistributionSummary)
- [x] `micro_batch_flush_duration_seconds` (Timer)
- [x] `micro_batch_dedupe` (Counter)
- [x] `micro_batch_buffer_limit_reached` (Counter)

- [x] JVM metrics (built-in Spring Boot Actuator)
  - [x] `process_cpu_usage`
  - [x] `jvm_memory_used_bytes`
  - [x] `jvm_gc_pause_seconds`

### ⚠️ Code Written, Needs Application Restart

- [ ] `http_server_requests_total` (Counter) - LoadTestMetricsFilter.kt
- [ ] `http_server_requests` (Timer) - LoadTestMetricsFilter.kt
- [ ] `http_server_errors` (Counter) - LoadTestMetricsFilter.kt
- [ ] `http_server_timeouts` (Counter) - LoadTestMetricsFilter.kt

- [ ] `async_tasks_started` (Counter) - AsyncTaskMetricsCollector.kt
- [ ] `async_tasks_completed` (Counter) - AsyncTaskMetricsCollector.kt
- [ ] `async_tasks_failed` (Counter) - AsyncTaskMetricsCollector.kt
- [ ] `async_tasks_timeout` (Counter) - AsyncTaskMetricsCollector.kt
- [ ] `async_task_duration_seconds` (Timer) - AsyncTaskMetricsCollector.kt
- [ ] `async_task_queue_time_seconds` (Timer) - AsyncTaskMetricsCollector.kt
- [ ] `async_task_execution_time_seconds` (Timer) - AsyncTaskMetricsCollector.kt

---

## 12. NEXT STEPS

1. **Restart Application** - Apply new metrics (LoadTestMetricsFilter, AsyncTaskMetricsCollector)

2. **Verify Metrics** - Check `/actuator/prometheus` endpoint:
   ```bash
   curl -s http://localhost:8080/actuator/prometheus | grep -E "http_server_requests|async_tasks"
   ```

3. **Run Load Test** - Execute load test with metrics collection

4. **Build Grafana Dashboard** - Import dashboard JSON

5. **Validate Interpretation** - Test interpretation scenarios against real data

---

## 13. QUICK REFERENCE

### Key Metrics for Bottleneck Detection

| Metric Type | Metric Name | Healthy Range | Warning |
|------------|-------------|---------------|---------|
| Admission Queue | `admission_control_queue_depth` | 0-500 | > 1000 |
| Executor Threads | `executor_active_threads` / `max` | 0.5-0.8 | > 0.9 |
| Rejection Rate | `rejected` / `requests` | < 1% | > 5% |
| Error Rate | `errors` / `requests` | < 5% | > 10% |
| P95 Latency | `http_server_requests` P95 | < 1s | > 2s |
| P99 Latency | `http_server_requests` P99 | < 2s | > 5s |
| GC Overhead | `jvm_gc_pause_sum` rate | < 5% | > 10% |
| CPU Usage | `process_cpu_usage` | 50-80% | > 90% |

### Query Templates

```promql
# Rate calculation (per second)
rate(METRIC_NAME[1m])

# Percentile from histogram
histogram_quantile(0.95, rate(METRIC_NAME_bucket[1m]))

# Ratio (e.g., error rate)
sum(rate(error_metric[1m])) / sum(rate(total_metric[1m]))

# Growth rate
deriv(METRIC_NAME[1m])
```

---

**END OF DOCUMENT**

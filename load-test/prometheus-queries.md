# Prometheus Queries for Observability Dashboard

## Admission Control Metrics

### Queue Depth (Gauge)
```promql
admission_control_queue_depth
```

### In-Flight Requests (Gauge)
```promql
admission_control_in_flight
```

### Rejection Rate (Counter → Rate)
```promql
rate(admission_control_rejected[1m])
```

### Early Rejection Rate (P0 FIX #2)
```promql
rate(admission_control_early_rejection[1m])
```

### Queue Full Events
```promql
rate(admission_control_queue_full[1m])
```

### Queue Wait Time Distribution
```promql
histogram_quantile(0.95, rate(admission_control_queue_wait_time_bucket[1m]))
histogram_quantile(0.99, rate(admission_control_queue_wait_time_bucket[1m]))
```

## Executor Metrics

### Active Threads
```promql
executor_pool_active{name="taskExecutor"}
```

### Queue Size
```promql
executor_queue_size{name="taskExecutor"}
```

### Rejection Rate
```promql
rate(executor_rejected[1m])
```

### Completed Tasks Rate
```promql
rate(executor_completed[1m])
```

## Async Task Metrics

### Task Started Rate
```promql
rate(async_tasks_started[1m])
```

### Task Completed Rate
```promql
rate(async_tasks_completed[1m])
```

### Task Failed Rate
```promql
rate(async_tasks_failed[1m])
```

### Task Timeout Rate
```promql
rate(async_tasks_timeout[1m])
```

### Task Duration (P95, P99)
```promql
histogram_quantile(0.95, rate(async_task_duration_bucket{result="success"}[1m]))
histogram_quantile(0.99, rate(async_task_duration_bucket{result="success"}[1m]))
```

### Task Duration by Result
```promql
histogram_quantile(0.95, rate(async_task_duration_bucket[1m]))
```

### Queue Time Distribution
```promql
histogram_quantile(0.95, rate(async_task_queue_time_bucket[1m]))
```

### Execution Time Distribution
```promql
histogram_quantile(0.95, rate(async_task_execution_time_bucket[1m]))
```

## Micro-Batching Metrics

### Flush Rate
```promql
rate(micro_batch_flush[1m])
```

### Buffer Size
```promql
micro_batch_buffer_size
```

### Flush Size Distribution
```promql
histogram_quantile(0.95, rate(micro_batch_flush_size_bucket[1m]))
```

### Flush Duration
```promql
histogram_quantile(0.95, rate(micro_batch_flush_duration_bucket[1m]))
```

### Dedupe Rate
```promql
rate(micro_batch_dedupe[1m])
```

### Buffer Limit Events
```promql
rate(micro_batch_buffer_limit_reached[1m])
```

## API Layer Metrics

### Request Rate (RPS)
```promql
rate(http_server_requests_total[1m])
```

### Error Rate
```promql
rate(http_server_errors[1m])
```

### Request Latency (P95, P99)
```promql
histogram_quantile(0.95, rate(http_server_requests_bucket[1m]))
histogram_quantile(0.99, rate(http_server_requests_bucket[1m]))
```

### Timeout Rate
```promql
rate(http_server_timeouts[1m])
```

### Success Rate
```promql
sum(rate(http_server_requests_total{result="success"}[1m])) /
sum(rate(http_server_requests_total[1m]))
```

## JVM Metrics

### CPU Usage
```promql
rate(process_cpu_seconds_total[1m])
```

### Heap Memory Usage
```promql
jvm_memory_used_bytes{area="heap"}
```

### GC Pause Time
```promql
rate(jvm_gc_pause_seconds_sum[1m])
```

### GC Pause Count
```promql
rate(jvm_gc_pause_seconds_count[1m])
```

## Combined Queries for Analysis

### End-to-End Latency Breakdown
```promql
# Queue time
histogram_quantile(0.95, rate(async_task_queue_time_bucket[1m]))

# Execution time
histogram_quantile(0.95, rate(async_task_execution_time_bucket[1m]))

# Total time
histogram_quantile(0.95, rate(http_server_requests_bucket[1m]))
```

### Throughput Analysis
```promql
# Admission throughput
rate(async_tasks_accepted[1m])

# Executor throughput
rate(async_tasks_completed[1m])

# API throughput
rate(http_server_requests_total{result="success"}[1m])
```

### Bottleneck Detection
```promql
# Queue buildup rate
deriv(admission_control_queue_depth[1m])

# Executor saturation
executor_pool_active{name="taskExecutor"} / executor_pool_size{name="taskExecutor"}

# Rejection correlation
rate(admission_control_rejected[1m]) / rate(async_tasks_started[1m])
```

### Saturation Indicators
```promql
# High queue depth (>80% capacity)
admission_control_queue_depth > (admission_control_queue_depth * 0.8)

# High rejection rate (>5%)
(rate(admission_control_rejected[1m]) / rate(async_tasks_started[1m])) > 0.05

# High error rate (>10%)
(rate(http_server_errors[1m]) / rate(http_server_requests_total[1m])) > 0.1
```

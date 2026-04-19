#!/usr/bin/env python3
"""
V5 Expectation Endpoint Load Test
- 10K IGNs from CSV → GET /api/v5/characters/{ign}/expectation
- Monitor queue depth, worker metrics via Prometheus endpoint
- Report response times, status codes, queue drain rate
"""

import csv
import time
import urllib.request
import urllib.parse
import json
import threading
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed

CSV_FILE = "module-app/src/main/resources/data/userIgn_List.csv"
BASE_URL = "http://localhost:8080/api/v5/characters"
PROMETHEUS_URL = "http://localhost:8080/actuator/prometheus"
COUNT = 10000
CONCURRENCY = 50
METRICS_INTERVAL = 3  # seconds
MAX_DRAIN_WAIT = 180  # 3 min max wait for queue drain

# Shared state
results_lock = threading.Lock()
metrics_data = []
stop_metrics = threading.Event()
start_time = time.time()


def parse_prometheus_text(text):
    """Parse Prometheus text format into dict of metric_name -> value"""
    metrics = {}
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        # metric_name{tags} value
        parts = line.split()
        if len(parts) >= 2:
            name_part = parts[0]
            value = float(parts[1])
            metrics[name_part] = value
    return metrics


def poll_metrics():
    """Poll Prometheus metrics endpoint periodically"""
    while not stop_metrics.is_set():
        try:
            req = urllib.request.Request(PROMETHEUS_URL)
            resp = urllib.request.urlopen(req, timeout=5)
            text = resp.read().decode("utf-8")
            metrics = parse_prometheus_text(text)

            row = {
                "timestamp": time.time(),
                "elapsed": time.time() - start_time,
            }

            # Extract PGMQ metrics per queue
            for key, value in metrics.items():
                if key.startswith("pgmq_"):
                    row[key] = value

            # Extract worker metrics
            for key, value in metrics.items():
                if key.startswith("expectation_worker_"):
                    row[key] = value

            # External API concurrent
            for key, value in metrics.items():
                if key.startswith("external_api_concurrent"):
                    row[key] = value

            metrics_data.append(row)
        except Exception as e:
            pass

        stop_metrics.wait(METRICS_INTERVAL)


def send_request(ign):
    """Send a single request and return (elapsed, status_code)"""
    encoded = urllib.parse.quote(ign)
    url = f"{BASE_URL}/{encoded}/expectation"

    req_start = time.time()
    try:
        req = urllib.request.Request(url)
        resp = urllib.request.urlopen(req, timeout=15)
        status = resp.getcode()
        resp.read()
    except urllib.error.HTTPError as e:
        status = e.code
    except Exception:
        status = 0

    elapsed = time.time() - req_start
    return elapsed, status


# ==================== Main ====================

# 1. Read IGNs
print(f"Reading {COUNT} IGNs from CSV...")
with open(CSV_FILE, "r", encoding="utf-8") as f:
    igns = [line.strip() for line in f if line.strip()][:COUNT]
print(f"Loaded {len(igns)} IGNs")

# 2. Start metrics polling thread
start_time = time.time()
metrics_thread = threading.Thread(target=poll_metrics, daemon=True)
metrics_thread.start()

# 3. Send requests
print(f"\nSending {len(igns)} requests (concurrency={CONCURRENCY})...")
print(f"{'Progress':>10} {'Sent':>6} {'200':>6} {'202':>6} {'Errors':>6} {'Avg(ms)':>8} {'Elapsed':>8}")

response_times = []
status_counter = Counter()
sent = 0

with ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
    futures = {executor.submit(send_request, ign): i for i, ign in enumerate(igns)}

    for future in as_completed(futures):
        elapsed, status = future.result()
        response_times.append(elapsed)
        status_counter[status] += 1
        sent += 1

        if sent % 1000 == 0:
            avg_ms = sum(response_times[-1000:]) / len(response_times[-1000:]) * 1000
            elapsed_s = time.time() - start_time
            print(
                f"{sent*100//len(igns):>9}% {sent:>6} "
                f"{status_counter.get(200, 0):>6} "
                f"{status_counter.get(202, 0):>6} "
                f"{sum(v for k, v in status_counter.items() if k not in (200, 202)):>6} "
                f"{avg_ms:>7.1f}ms {elapsed_s:>7.1f}s"
            )

request_end_time = time.time()
request_phase_s = request_end_time - start_time

print(f"\n{'='*70}")
print(f"REQUEST PHASE COMPLETE")
print(f"{'='*70}")
print(f"Total requests:     {len(igns)}")
print(f"Request phase:      {request_phase_s:.1f}s")
print(f"Throughput:         {len(igns)/request_phase_s:.1f} req/s")
print(f"Status 200 (HIT):   {status_counter.get(200, 0)}")
print(f"Status 202 (QUEUE): {status_counter.get(202, 0)}")
print(f"Errors:             {sum(v for k, v in status_counter.items() if k not in (200, 202))}")

# 4. Monitor queue drain
queued_count = status_counter.get(202, 0)
if queued_count > 0:
    print(f"\n{queued_count} tasks queued. Monitoring queue drain (max wait: {MAX_DRAIN_WAIT}s)...")

    drain_start = time.time()
    last_depth = None
    peak_depth = 0
    drain_complete = False

    while time.time() - drain_start < MAX_DRAIN_WAIT:
        if not metrics_data:
            time.sleep(2)
            continue

        latest = metrics_data[-1]
        depth = None

        # Sum all pgmq_queue_depth gauges across queues
        for key, val in latest.items():
            if "pgmq_queue_depth" in key:
                if depth is None:
                    depth = 0
                depth += val

        if depth is not None:
            if depth > peak_depth:
                peak_depth = depth
            if depth != last_depth:
                elapsed = latest["elapsed"]
                print(f"  [{elapsed:>7.1f}s] Queue depth: {depth:.0f}")
                last_depth = depth

            if depth < 1:
                drain_elapsed = time.time() - drain_start
                print(f"\n  Queue DRAINED at {latest['elapsed']:.1f}s (drain phase: {drain_elapsed:.1f}s)")
                drain_complete = True
                break

        time.sleep(3)

    if not drain_complete:
        print(f"\n  Queue drain monitoring timed out after {MAX_DRAIN_WAIT}s")
        if last_depth is not None:
            print(f"  Final queue depth: {last_depth:.0f}")
else:
    print("\nNo tasks queued (all cache hits). No drain monitoring needed.")

# 5. Stop metrics polling
stop_metrics.set()
metrics_thread.join(timeout=5)

# 6. Final report
end_time = time.time()
total_wall_time = end_time - start_time

response_times.sort()
p50 = response_times[len(response_times) // 2]
p95 = response_times[int(len(response_times) * 0.95)]
p99 = response_times[int(len(response_times) * 0.99)]

print(f"\n{'='*70}")
print(f"LOAD TEST SUMMARY")
print(f"{'='*70}")
print(f"Total requests:     {len(igns)}")
print(f"Concurrency:        {CONCURRENCY}")
print(f"Total wall time:    {total_wall_time:.1f}s")
print(f"Request phase:      {request_phase_s:.1f}s ({len(igns)/request_phase_s:.1f} req/s)")
print(f"")
print(f"Status Codes:")
for code in sorted(status_counter.keys()):
    label = {200: "OK (cached)", 202: "Accepted (queued)", 500: "Server Error", 503: "Queue Full"}.get(code, "")
    print(f"  {code}: {status_counter[code]:>6}  {label}")
print(f"")
print(f"Response Time Distribution:")
print(f"  Min:  {response_times[0]*1000:>8.1f}ms")
print(f"  p50:  {p50*1000:>8.1f}ms")
print(f"  p95:  {p95*1000:>8.1f}ms")
print(f"  p99:  {p99*1000:>8.1f}ms")
print(f"  Max:  {response_times[-1]*1000:>8.1f}ms")
print(f"  Avg:  {sum(response_times)/len(response_times)*1000:>8.1f}ms")
print(f"")

# Queue metrics timeline
if metrics_data:
    print(f"Queue Metrics Timeline:")
    print(f"  {'Elapsed':>8} {'Depth':>8} {'Inflight':>10} {'Concurrent':>12}")

    # Sample at reasonable intervals
    sampled = metrics_data[::max(1, len(metrics_data) // 30)]
    for row in sampled:
        depth = 0
        inflight = 0
        concurrent = 0
        for key, val in row.items():
            if "pgmq_queue_depth" in key:
                depth += val
            if "pgmq_worker_inflight" in key:
                inflight += val
            if "pgmq_worker_concurrent" in key:
                concurrent += val
        if depth > 0 or inflight > 0 or concurrent > 0:
            print(f"  {row['elapsed']:>7.1f}s {depth:>8.0f} {inflight:>10.0f} {concurrent:>12.0f}")

# Worker throughput estimation
if queued_count > 0 and drain_complete:
    worker_throughput = queued_count / drain_elapsed
    print(f"\nWorker Throughput:")
    print(f"  Tasks processed:  {queued_count}")
    print(f"  Drain time:       {drain_elapsed:.1f}s")
    print(f"  Throughput:       {worker_throughput:.1f} tasks/s")
elif queued_count > 0:
    # Estimate from metrics data
    depths = []
    for row in metrics_data:
        d = sum(v for k, v in row.items() if "pgmq_queue_depth" in k)
        depths.append((row["elapsed"], d))
    if len(depths) >= 2:
        # Find peak and estimate drain rate
        peak_idx = max(range(len(depths)), key=lambda i: depths[i][1])
        if peak_idx < len(depths) - 1:
            remaining = [(t, d) for t, d in depths[peak_idx:] if d > 0]
            if len(remaining) >= 2:
                t1, d1 = remaining[0]
                t2, d2 = remaining[-1]
                dt = t2 - t1
                dd = d1 - d2
                if dt > 0:
                    est_throughput = dd / dt
                    print(f"\nWorker Throughput (estimated from metrics):")
                    print(f"  ~{est_throughput:.1f} tasks/s")

print(f"\n{'='*70}")

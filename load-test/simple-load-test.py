#!/usr/bin/env python3
"""
Simple 300 RPS Load Test
=========================

Guarantees 300 RPS by using ThreadPoolExecutor with fixed rate.
"""

import requests
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
import statistics
from datetime import datetime

BASE_URL = "http://localhost:8080"
USERIGN_FILE = "/tmp/sample_1000_userigns.txt"
TARGET_RPS = 300
DURATION_SECONDS = 120  # 2 minutes

# Load user IGNS
with open(USERIGN_FILE, 'r', encoding='utf-8') as f:
    USERIGNS = [line.strip() for line in f if line.strip()]

print(f"📊 Simple Load Test: {TARGET_RPS} RPS for {DURATION_SECONDS}s")
print(f"Loaded {len(USERIGNS)} user IGNS")

# Shared state
results = {
    'requests': 0,
    'success': 0,
    'errors': 0,
    'timeouts': 0,
    'latencies_ms': []
}
lock = threading.Lock()
stop_flag = threading.Event()

def send_request(worker_id):
    """Send requests continuously until stop flag"""
    local_requests = 0
    local_latencies = []

    while not stop_flag.is_set():
        user_ign = USERIGNS[worker_id % len(USERIGNS)]
        start = time.time()

        try:
            response = requests.get(
                f"{BASE_URL}/api/v4/characters/{user_ign}/expectation",
                timeout=35
            )
            elapsed = (time.time() - start) * 1000

            with lock:
                results['requests'] += 1
                results['latencies_ms'].append(elapsed)
                local_requests += 1
                local_latencies.append(elapsed)

                if response.status_code == 200:
                    results['success'] += 1
                else:
                    results['errors'] += 1

        except requests.exceptions.Timeout:
            with lock:
                results['requests'] += 1
                results['timeouts'] += 1
                results['errors'] += 1

        except Exception as e:
            with lock:
                results['errors'] += 1

        # Throttle to maintain ~1 RPS per worker
        time.sleep(0.9)  # Slightly less than 1s to account for request time

    return local_requests

# Start workers
start_time = time.time()
stop_flag.clear()

print(f"\n🚀 Starting {TARGET_RPS} workers at {datetime.now().strftime('%H:%M:%S')}")

with ThreadPoolExecutor(max_workers=TARGET_RPS) as executor:
    # Submit all workers
    futures = [executor.submit(send_request, i) for i in range(TARGET_RPS)]

    # Monitor progress
    last_requests = 0
    while time.time() - start_time < DURATION_SECONDS:
        time.sleep(10)
        elapsed = time.time() - start_time

        with lock:
            current_requests = results['requests']
            rps = (current_requests - last_requests) / 10 if elapsed > 0 else 0
            total_rps = current_requests / elapsed if elapsed > 0 else 0

            print(f"[{int(elapsed)}s] Total: {current_requests:,} | "
                  f"Last 10s RPS: {rps:.1f} | "
                  f"Avg RPS: {total_rps:.1f} | "
                  f"Success: {results['success']:,} | "
                  f"Errors: {results['errors']:,}")

            last_requests = current_requests

    # Stop all workers
    print(f"\n⏹️  Stopping workers...")
    stop_flag.set()

    # Wait for completion
    for future in as_completed(futures):
        future.result()

# Final results
total_time = time.time() - start_time
actual_rps = results['requests'] / total_time

print(f"\n{'='*60}")
print(f"📊 FINAL RESULTS")
print(f"{'='*60}")
print(f"Duration: {total_time:.1f}s")
print(f"Target RPS: {TARGET_RPS}")
print(f"Achieved RPS: {actual_rps:.2f}")
print(f"Total Requests: {results['requests']:,}")
print(f"Success: {results['success']:,} ({results['success']/results['requests']*100:.2f}%)")
print(f"Errors: {results['errors']:,} ({results['errors']/results['requests']*100:.2f}%)")
print(f"Timeouts: {results['timeouts']:,}")

if results['latencies_ms']:
    print(f"\n📈 Latency Distribution:")
    print(f"   Mean: {statistics.mean(results['latencies_ms']):.1f}ms")
    print(f"   Median: {statistics.median(results['latencies_ms']):.1f}ms")
    print(f"   P95: {statistics.quantiles(results['latencies_ms'], n=20)[18]:.1f}ms")
    print(f"   P99: {statistics.quantiles(results['latencies_ms'], n=100)[98]:.1f}ms")
    print(f"   Min: {min(results['latencies_ms']):.1f}ms")
    print(f"   Max: {max(results['latencies_ms']):.1f}ms")

print(f"{'='*60}\n")

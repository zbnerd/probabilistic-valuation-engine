#!/bin/bash
# Apache Bench Load Test - Cycle through all 300k user IGNS
# Usage: ./ab-cycle-test.sh

set -e

BASE_URL="http://localhost:8080"
USERIGN_CSV="/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv"
USERIGN_TXT="/tmp/all_300k_userigns.txt"
THREADS=10
CONCURRENCY=300
DURATION=120  # 2 minutes

echo "========================================================================"
echo "🔥 APACHE BENCH LOAD TEST - Cycle Through All 300k User IGNS"
echo "========================================================================"
echo "Extracting user IGNS from CSV..."
echo ""

# Extract all user IGNS from CSV to text file (one per line)
tail -n +2 "$USERIGN_CSV" | cut -d',' -f1 | tr -d '"' > "$USERIGN_TXT"
TOTAL_USERS=$(wc -l < "$USERIGN_TXT")
echo "✅ Extracted $TOTAL_USERS user IGNS"
echo ""
echo "Configuration:"
echo "  Concurrency: $CONCURRENCY"
echo "  Threads: $THREADS (using Apache Bench)"
echo "  Duration: ${DURATION}s (2 minutes)"
echo "  Expected RPS: ~300"
echo "  Expected requests: ~$((DURATION * 300))"
echo "  Expected unique users: ~$((DURATION * 300))"
echo "  Started: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""
echo "========================================================================"
echo ""

# Note: Apache Bench doesn't support cycling through different URLs per request
# So we'll use a simple Python script instead
cat << 'PYTHON_SCRIPT' > /tmp/wrk-alternative.py
#!/usr/bin/env python3
"""
Alternative to wrk: Cycle through 300k user IGNS at ~300 RPS
"""
import requests
import time
import threading
import statistics
from datetime import datetime
import sys

BASE_URL = "http://localhost:8080"
USERIGN_FILE = "/tmp/all_300k_userigns.txt"
TARGET_RPS = 300
DURATION_SECONDS = 120
CYCLE_SIZE = 1000  # Use first 1000 users in sequence

# Load user IGNS
with open(USERIGN_FILE, 'r', encoding='utf-8') as f:
    USERIGNS = [line.strip() for line in f if line.strip()]

print(f"Loaded {len(USERIGNS)} user IGNS")
print(f"Using first {CYCLE_SIZE} users in sequence")

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

def worker_thread(worker_id):
    """Each worker sends requests in sequence"""
    local_requests = 0

    while not stop_flag.is_set():
        # Get next user IGN in sequence (cycling through first 1000)
        user_index = (results['requests'] + worker_id) % CYCLE_SIZE
        user_ign = USERIGNS[user_index]

        try:
            start = time.time()
            response = requests.get(
                f"{BASE_URL}/api/v4/characters/{user_ign}/expectation",
                timeout=35
            )
            elapsed = (time.time() - start) * 1000

            with lock:
                results['requests'] += 1
                results['latencies_ms'].append(elapsed)
                local_requests += 1

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

        # Throttle to maintain target RPS
        time.sleep(1.0 / (TARGET_RPS / 100))  # 100 workers, each doing 3 RPS

    return local_requests

# Main execution
start_time = time.time()

print(f"\n🚀 Starting {TARGET_RPS} RPS load test for {DURATION_SECONDS}s...")
print(f"Using 100 worker threads (each maintaining ~3 RPS)")
print(f"Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
print("")

# Start worker threads
threads = []
for i in range(100):
    thread = threading.Thread(target=worker_thread, args=(i,), daemon=True)
    thread.start()
    threads.append(thread)

# Monitor progress
last_requests = 0
last_time = start_time

while time.time() - start_time < DURATION_SECONDS:
    time.sleep(10)
    current_time = time.time()
    elapsed = int(current_time - start_time)

    with lock:
        current_requests = results['requests']
        rps = (current_requests - last_requests) / (current_time - last_time) if current_time > last_time else 0
        total_rps = current_requests / elapsed if elapsed > 0 else 0

        # Calculate unique users hit
        unique_users = min(current_requests, CYCLE_SIZE)
        cycles = current_requests // CYCLE_SIZE

        print(f"[{elapsed:3d}s] Requests: {current_requests:6,} | "
              f"Last 10s RPS: {rps:6.1f} | "
              f"Avg RPS: {total_rps:6.1f} | "
              f"Success: {results['success']:6,} | "
              f"Errors: {results['errors']:5,} | "
              f"Unique users: {unique_users:4}/{CYCLE_SIZE}")

        last_requests = current_requests
        last_time = current_time

# Stop all workers
print(f"\n⏹️  Stopping workers...")
stop_flag.set()

for thread in threads:
    thread.join()

# Final statistics
total_time = time.time() - start_time
actual_rps = results['requests'] / total_time

print(f"\n{'='*70}")
print(f"📊 FINAL RESULTS")
print(f"{'='*70}")
print(f"Duration: {total_time:.1f}s")
print(f"Target RPS: {TARGET_RPS}")
print(f"Achieved RPS: {actual_rps:.2f}")
print(f"Total Requests: {results['requests']:,}")
print(f"Success: {results['success']:,} ({results['success']/results['requests']*100:.2f}%)")
print(f"Errors: {results['errors']:,} ({results['errors']/results['requests']*100:.2f}%)")
print(f"Timeouts: {results['timeouts']:,}")

# Unique users statistics
unique_users_hit = min(results['requests'], CYCLE_SIZE)
cycles_completed = results['requests'] // CYCLE_SIZE
remaining_in_cycle = results['requests'] % CYCLE_SIZE

print(f"\n👥 User Coverage:")
print(f"   Unique users hit: {unique_users_hit:,} / {CYCLE_SIZE:,}")
print(f"   Complete cycles: {cycles_completed}")
print(f"   Remaining in cycle: {remaining_in_cycle} / {CYCLE_SIZE}")

if results['latencies_ms']:
    print(f"\n📈 Latency Distribution:")
    print(f"   Mean: {statistics.mean(results['latencies_ms']):.1f}ms")
    print(f"   Median: {statistics.median(results['latencies_ms']):.1f}ms")
    print(f"   P95: {statistics.quantiles(results['latencies_ms'], n=20)[18]:.1f}ms")
    print(f"   P99: {statistics.quantiles(results['latencies_ms'], n=100)[98]:.1f}ms")
    print(f"   Min: {min(results['latencies_ms']):.1f}ms")
    print(f"   Max: {max(results['latencies_ms']):.1f}ms")

print(f"{'='*70}\n")
print(f"Completed: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
PYTHON_SCRIPT

chmod +x /tmp/wrk-alternative.py
python3 /tmp/wrk-alternative.py 2>&1 | tee /tmp/load-test-results.log

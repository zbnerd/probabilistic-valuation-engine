#!/usr/bin/env python3
"""
True 300 RPS Load Test Using Multiprocessing
==============================================

Uses multiprocessing to bypass Python GIL and achieve true parallelism.
Each process maintains its own RPS target.
"""

import requests
import time
import multiprocessing
import statistics
from datetime import datetime
import sys

BASE_URL = "http://localhost:8080"
USERIGN_FILE = "/tmp/sample_1000_userigns.txt"
TARGET_RPS = 300
DURATION_SECONDS = 120  # 2 minutes

# Load user IGNS (done once in main process)
with open(USERIGN_FILE, 'r', encoding='utf-8') as f:
    USERIGNS = [line.strip() for line in f if line.strip()]

def worker_process(process_id, duration, rps_per_process, user_igns, results_queue):
    """
    Each process sends requests at its target RPS independently.
    No GIL contention - true parallelism.
    """
    stop_time = time.time() + duration
    request_interval = 1.0 / rps_per_process

    local_results = {
        'requests': 0,
        'success': 0,
        'errors': 0,
        'timeouts': 0,
        'latencies_ms': []
    }

    while time.time() < stop_time:
        user_ign = user_igns[process_id % len(user_igns)]
        start = time.time()

        try:
            response = requests.get(
                f"{BASE_URL}/api/v4/characters/{user_ign}/expectation",
                timeout=35
            )
            elapsed = (time.time() - start) * 1000

            local_results['requests'] += 1
            local_results['latencies_ms'].append(elapsed)

            if response.status_code == 200:
                local_results['success'] += 1
            else:
                local_results['errors'] += 1

        except requests.exceptions.Timeout:
            local_results['requests'] += 1
            local_results['timeouts'] += 1
            local_results['errors'] += 1

        except Exception:
            local_results['errors'] += 1

        # Maintain exact RPS
        elapsed_request_time = time.time() - start
        sleep_time = max(0, request_interval - (elapsed_request_time / 1000.0))
        time.sleep(sleep_time)

    # Send results back to main process
    results_queue.put((process_id, local_results))

def monitor_progress(duration, results_queue):
    """Monitor progress by checking Prometheus metrics"""
    PROMETHEUS_URL = "http://localhost:8080/actuator/prometheus"

    def get_completed_tasks():
        try:
            response = requests.get(PROMETHEUS_URL, timeout=2)
            total = 0
            for line in response.text.split('\n'):
                if 'executor_completed_tasks_total' in line and '{' not in line:
                    parts = line.split(' ')
                    if len(parts) >= 2:
                        try:
                            total += float(parts[-1])
                        except:
                            pass
            return total
        except:
            return 0

    start_time = time.time()
    last_count = get_completed_tasks()

    while time.time() - start_time < duration:
        time.sleep(10)
        elapsed = int(time.time() - start_time)

        current_count = get_completed_tasks()
        delta = current_count - last_count
        rps = delta / 10 if elapsed > 0 else 0

        print(f"[{elapsed}s] Completed tasks: {current_count:,} | "
              f"Last 10s RPS: {rps:.1f}")

        last_count = current_count

def main():
    print("="*60)
    print(f"🔥 TRUE 300 RPS LOAD TEST (Multiprocessing)")
    print("="*60)
    print(f"Target RPS: {TARGET_RPS}")
    print(f"Duration: {DURATION_SECONDS}s")
    print(f"User IGNS: {len(USERIGNS)}")
    print(f"Processes: {multiprocessing.cpu_count()}")
    print(f"Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("="*60)

    # Calculate RPS per process
    num_processes = multiprocessing.cpu_count()
    rps_per_process = TARGET_RPS / num_processes

    print(f"\n📊 Configuration:")
    print(f"   CPU cores: {num_processes}")
    print(f"   Processes: {num_processes}")
    print(f"   RPS per process: {rps_per_process:.2f}")

    # Start processes
    results_queue = multiprocessing.Manager().Queue()
    processes = []

    # Start monitor thread
    monitor_thread = multiprocessing.Process(
        target=monitor_progress,
        args=(DURATION_SECONDS, results_queue)
    )
    monitor_thread.start()

    # Start worker processes
    start_time = time.time()
    for i in range(num_processes):
        p = multiprocessing.Process(
            target=worker_process,
            args=(i, DURATION_SECONDS, rps_per_process, USERIGNS, results_queue)
        )
        p.start()
        processes.append(p)

    # Wait for completion
    for p in processes:
        p.join()

    monitor_thread.join()

    # Collect results
    all_results = {
        'requests': 0,
        'success': 0,
        'errors': 0,
        'timeouts': 0,
        'latencies_ms': []
    }

    while not results_queue.empty():
        _, result = results_queue.get()
        all_results['requests'] += result['requests']
        all_results['success'] += result['success']
        all_results['errors'] += result['errors']
        all_results['timeouts'] += result['timeouts']
        all_results['latencies_ms'].extend(result['latencies_ms'])

    # Final statistics
    total_time = time.time() - start_time
    actual_rps = all_results['requests'] / total_time

    print(f"\n{'='*60}")
    print(f"📊 FINAL RESULTS")
    print(f"{'='*60}")
    print(f"Duration: {total_time:.1f}s")
    print(f"Target RPS: {TARGET_RPS}")
    print(f"Achieved RPS: {actual_rps:.2f}")
    print(f"Total Requests: {all_results['requests']:,}")
    print(f"Success: {all_results['success']:,} ({all_results['success']/all_results['requests']*100:.2f}%)")
    print(f"Errors: {all_results['errors']:,} ({all_results['errors']/all_results['requests']*100:.2f}%)")
    print(f"Timeouts: {all_results['timeouts']:,}")

    if all_results['latencies_ms']:
        print(f"\n📈 Latency Distribution:")
        print(f"   Mean: {statistics.mean(all_results['latencies_ms']):.1f}ms")
        print(f"   Median: {statistics.median(all_results['latencies_ms']):.1f}ms")
        print(f"   P95: {statistics.quantiles(all_results['latencies_ms'], n=20)[18]:.1f}ms")
        print(f"   P99: {statistics.quantiles(all_results['latencies_ms'], n=100)[98]:.1f}ms")
        print(f"   Min: {min(all_results['latencies_ms']):.1f}ms")
        print(f"   Max: {max(all_results['latencies_ms']):.1f}ms")

    print(f"{'='*60}\n")

if __name__ == '__main__':
    main()

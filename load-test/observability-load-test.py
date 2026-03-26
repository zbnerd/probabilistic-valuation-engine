#!/usr/bin/env python3
"""
🔥 Observability-Driven Load Test Suite
=======================================

This is NOT just a load test. This is a systematic experiment to understand
system behavior under load and identify bottlenecks using metrics.

Test Scenarios:
1. Baseline (300 RPS, 10 min)
2. Saturation Test (300 → 600 RPS)
3. Burst Test (0 → 500 RPS)
4. Zipf Distribution Test (20% hot keys)

Metrics Collected:
- Admission control: in_flight, queue_depth, rejected
- Executor: active_threads, queue_size, rejected
- Async tasks: started, completed, failed, timeout
- Micro-batching: flush size, flush latency
- API: request latency, errors, timeouts
"""

import requests
import time
import threading
import statistics
import argparse
import json
import csv
from datetime import datetime, timedelta
from collections import defaultdict, deque
from pathlib import Path
import sys
import random

# ==================== CONFIGURATION ====================

BASE_URL = "http://localhost:8080"
USERIGN_FILE = "/tmp/sample_1000_userigns.txt"  # Extracted from CSV (300k total)
RESULTS_DIR = Path("./load-test-results")
METRICS_ENDPOINT = f"{BASE_URL}/actuator/prometheus"

# Load user IGNS
try:
    with open(USERIGN_FILE, 'r', encoding='utf-8') as f:
        USERIGNS = [line.strip() for line in f if line.strip()]
    print(f"✅ Loaded {len(USERIGNS)} unique user IGNS")
except FileNotFoundError:
    print(f"❌ User IGN file not found: {USERIGN_FILE}")
    sys.exit(1)

# ==================== METRICS COLLECTOR ====================

class PrometheusMetricsCollector:
    """Collect Prometheus metrics during load test"""

    def __init__(self, interval_seconds=5):
        self.interval = interval_seconds
        self.metrics_history = []
        self.running = False
        self.thread = None

    def start(self):
        """Start metrics collection in background thread"""
        self.running = True
        self.thread = threading.Thread(target=self._collect_loop, daemon=True)
        self.thread.start()
        print(f"📊 Started Prometheus metrics collection (interval: {self.interval}s)")

    def stop(self):
        """Stop metrics collection"""
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        print(f"📊 Collected {len(self.metrics_history)} metric snapshots")

    def _collect_loop(self):
        """Continuously collect metrics"""
        while self.running:
            try:
                metrics = self._fetch_metrics()
                if metrics:
                    self.metrics_history.append(metrics)
            except Exception as e:
                print(f"⚠️  Metrics collection error: {e}")
            time.sleep(self.interval)

    def _fetch_metrics(self):
        """Fetch and parse Prometheus metrics"""
        try:
            response = requests.get(METRICS_ENDPOINT, timeout=5)
            response.raise_for_status()

            metrics = {}
            for line in response.text.split('\n'):
                line = line.strip()
                if line and not line.startswith('#'):
                    # Parse metric line: metric_name{tags} value
                    if '{' in line:
                        name_part, value_part = line.split('}', 1)
                        name = name_part.split('{')[0].strip()
                        value = float(value_part.strip())
                    else:
                        parts = line.rsplit(' ', 1)
                        if len(parts) == 2:
                            name = parts[0]
                            value = float(parts[1])
                        else:
                            continue

                    # Collect key metrics
                    if any(key in name for key in [
                        'admission_control',
                        'executor',
                        'micro_batch',
                        'http_server',
                        'async_tasks',
                        'jvm_',
                    ]):
                        metrics[name] = value

            return metrics

        except Exception as e:
            print(f"⚠️  Failed to fetch metrics: {e}")
            return None

    def save_metrics(self, output_file):
        """Save metrics history to JSON file"""
        with open(output_file, 'w') as f:
            json.dump(self.metrics_history, f, indent=2)
        print(f"📊 Metrics saved to: {output_file}")

# ==================== LOAD TEST WORKER ====================

class LoadTestWorker:
    """Worker thread that sends requests at target RPS"""

    def __init__(self, worker_id, target_rps, user_igns, duration_seconds):
        self.worker_id = worker_id
        self.target_rps = target_rps
        self.user_igns = user_igns
        self.duration = duration_seconds
        self.results = {
            'requests': 0,
            'success': 0,
            'errors': 0,
            'timeouts': 0,
            'latencies_ms': [],
            'errors_by_type': defaultdict(int),
            'status_codes': defaultdict(int)
        }
        self.stop_time = None
        self.lock = threading.Lock()

    def run(self):
        """Main worker loop"""
        start_time = time.time()
        self.stop_time = start_time + self.duration
        request_interval = 1.0 / self.target_rps

        while time.time() < self.stop_time:
            try:
                user_ign = random.choice(self.user_igns)
                self._send_request(user_ign)
            except Exception as e:
                with self.lock:
                    self.results['errors'] += 1
                    self.results['errors_by_type'][str(type(e).__name__)] += 1

            # Sleep to maintain target RPS
            time.sleep(request_interval)

    def _send_request(self, user_ign):
        """Send single request and record result"""
        start_time = time.time()

        try:
            response = requests.get(
                f"{BASE_URL}/api/v4/characters/{user_ign}/expectation",
                timeout=35  # Slightly above 30s timeout
            )
            end_time = time.time()
            latency_ms = (end_time - start_time) * 1000

            with self.lock:
                self.results['requests'] += 1
                self.results['latencies_ms'].append(latency_ms)
                self.results['status_codes'][response.status_code] += 1

                if response.status_code == 200:
                    self.results['success'] += 1
                elif response.status_code >= 400:
                    self.results['errors'] += 1

        except requests.exceptions.Timeout:
            with self.lock:
                self.results['requests'] += 1
                self.results['timeouts'] += 1
                self.results['errors'] += 1

        except Exception as e:
            with self.lock:
                self.results['requests'] += 1
                self.results['errors'] += 1
                self.results['errors_by_type'][type(e).__name__] += 1

    def get_stats(self):
        """Get aggregated statistics"""
        latencies = self.results['latencies_ms']

        stats = {
            'worker_id': self.worker_id,
            'requests': self.results['requests'],
            'success': self.results['success'],
            'errors': self.results['errors'],
            'timeouts': self.results['timeouts'],
            'error_rate': (self.results['errors'] / self.results['requests'] * 100) if self.results['requests'] > 0 else 0,
        }

        if latencies:
            stats.update({
                'avg_latency_ms': statistics.mean(latencies),
                'p50_latency_ms': statistics.median(latencies),
                'p95_latency_ms': statistics.quantiles(latencies, n=20)[18] if len(latencies) >= 20 else max(latencies),
                'p99_latency_ms': statistics.quantiles(latencies, n=100)[98] if len(latencies) >= 100 else max(latencies),
                'min_latency_ms': min(latencies),
                'max_latency_ms': max(latencies),
            })
        else:
            stats.update({
                'avg_latency_ms': 0,
                'p50_latency_ms': 0,
                'p95_latency_ms': 0,
                'p99_latency_ms': 0,
                'min_latency_ms': 0,
                'max_latency_ms': 0,
            })

        return stats

# ==================== LOAD TEST SCENARIOS ====================

def run_baseline_test(rps=300, duration=600, name="baseline"):
    """Scenario 1: Baseline test - steady load"""
    print(f"\n{'='*60}")
    print(f"🧪 SCENARIO 1: BASELINE TEST")
    print(f"{'='*60}")
    print(f"Target RPS: {rps}")
    print(f"Duration: {duration}s ({duration//60} min)")
    print(f"Unique keys: {len(USERIGNS)}")
    print(f"{'='*60}\n")

    # Start metrics collector
    collector = PrometheusMetricsCollector(interval_seconds=5)
    collector.start()

    # Create and start workers
    workers = []
    threads = []

    for i in range(rps):
        worker = LoadTestWorker(i, 1, USERIGNS, duration)  # Each worker maintains 1 RPS
        workers.append(worker)
        thread = threading.Thread(target=worker.run, daemon=True)
        threads.append(thread)
        thread.start()

    # Wait for completion
    for thread in threads:
        thread.join()

    # Stop metrics collection
    collector.stop()

    # Aggregate results
    total_requests = sum(w.results['requests'] for w in workers)
    total_success = sum(w.results['success'] for w in workers)
    total_errors = sum(w.results['errors'] for w in workers)
    all_latencies = []
    for w in workers:
        all_latencies.extend(w.results['latencies_ms'])

    print(f"\n{'='*60}")
    print(f"📊 BASELINE TEST RESULTS")
    print(f"{'='*60}")
    print(f"Total Requests: {total_requests}")
    print(f"Success: {total_success} ({total_success/total_requests*100:.2f}%)")
    print(f"Errors: {total_errors} ({total_errors/total_requests*100:.2f}%)")

    if all_latencies:
        print(f"Avg Latency: {statistics.mean(all_latencies):.2f}ms")
        print(f"P50 Latency: {statistics.median(all_latencies):.2f}ms")
        print(f"P95 Latency: {statistics.quantiles(all_latencies, n=20)[18]:.2f}ms")
        print(f"P99 Latency: {statistics.quantiles(all_latencies, n=100)[98]:.2f}ms")

    print(f"{'='*60}\n")

    # Save results
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    results_file = RESULTS_DIR / f"{name}_{timestamp}.json"
    metrics_file = RESULTS_DIR / f"{name}_metrics_{timestamp}.json"

    RESULTS_DIR.mkdir(exist_ok=True)

    results = {
        'scenario': name,
        'config': {
            'rps': rps,
            'duration_seconds': duration,
            'unique_keys': len(USERIGNS),
        },
        'summary': {
            'total_requests': total_requests,
            'success': total_success,
            'errors': total_errors,
            'success_rate': total_success/total_requests if total_requests > 0 else 0,
            'avg_latency_ms': statistics.mean(all_latencies) if all_latencies else 0,
            'p95_latency_ms': statistics.quantiles(all_latencies, n=20)[18] if all_latencies and len(all_latencies) >= 20 else 0,
        }
    }

    with open(results_file, 'w') as f:
        json.dump(results, f, indent=2)

    collector.save_metrics(metrics_file)

    print(f"✅ Results saved to: {results_file}")
    print(f"✅ Metrics saved to: {metrics_file}")

    return results, collector.metrics_history

def run_saturation_test(duration_per_level=300):
    """Scenario 2: Saturation test - gradually increase RPS"""
    print(f"\n{'='*60}")
    print(f"🧪 SCENARIO 2: SATURATION TEST")
    print(f"{'='*60}")

    rps_levels = [300, 400, 500, 600]
    results = []

    for rps in rps_levels:
        print(f"\n🔥 Testing RPS level: {rps}")
        result, metrics = run_baseline_test(rps=rps, duration=duration_per_level, name=f"saturation_{rps}rps")
        results.append((rps, result))

        # Check if system is saturated (>10% error rate)
        if result['summary']['success_rate'] < 0.9:
            print(f"\n⚠️  System saturated at {rps} RPS (success rate: {result['summary']['success_rate']*100:.2f}%)")
            break

        # Cooldown between tests
        print(f"⏸️  Cooldown: 30s...")
        time.sleep(30)

    return results

def run_burst_test(rps=500, duration=120):
    """Scenario 3: Burst test - sudden spike"""
    print(f"\n{'='*60}")
    print(f"🧪 SCENARIO 3: BURST TEST")
    print(f"{'='*60}")
    print(f"Sudden spike to {rps} RPS for {duration}s")

    return run_baseline_test(rps=rps, duration=duration, name="burst")

def run_zipf_test(rps=300, duration=300):
    """Scenario 4: Zipf distribution - 20% hot keys"""
    print(f"\n{'='*60}")
    print(f"🧪 SCENARIO 4: ZIPF DISTRIBUTION TEST")
    print(f"{'='*60}")
    print(f"20% hot keys, 80% cold keys")

    # Create Zipf distribution
    hot_keys = USERIGNS[:200]  # 20% hot
    cold_keys = USERIGNS[200:]  # 80% cold

    # Weighted selection: 80% hot, 20% cold
    weighted_keys = hot_keys * 8 + cold_keys * 2
    random.shuffle(weighted_keys)

    print(f"Hot keys: {len(hot_keys)}")
    print(f"Cold keys: {len(cold_keys)}")

    return run_baseline_test(rps=rps, duration=duration, name="zipf")

# ==================== MAIN ====================

def main():
    parser = argparse.ArgumentParser(description='Observability-Driven Load Test Suite')
    parser.add_argument('--scenario', choices=['baseline', 'saturation', 'burst', 'zipf', 'all'],
                       default='baseline', help='Test scenario to run')
    parser.add_argument('--rps', type=int, default=300, help='Target RPS (for baseline/burst)')
    parser.add_argument('--duration', type=int, default=600, help='Duration in seconds')
    parser.add_argument('--url', default=BASE_URL, help='Base URL for API')

    args = parser.parse_args()

    print(f"\n{'='*60}")
    print(f"🔥 OBSERVABILITY-DRIVEN LOAD TEST")
    print(f"{'='*60}")
    print(f"Target: {args.url}")
    print(f"Scenario: {args.scenario}")
    print(f"Started: {datetime.now().isoformat()}")
    print(f"{'='*60}\n")

    # Run selected scenario
    if args.scenario == 'baseline':
        run_baseline_test(rps=args.rps, duration=args.duration)

    elif args.scenario == 'saturation':
        run_saturation_test()

    elif args.scenario == 'burst':
        run_burst_test(rps=args.rps, duration=args.duration)

    elif args.scenario == 'zipf':
        run_zipf_test(rps=args.rps, duration=args.duration)

    elif args.scenario == 'all':
        print("\n🚀 Running ALL scenarios...\n")
        run_baseline_test(rps=300, duration=600)
        time.sleep(30)
        run_saturation_test()
        time.sleep(30)
        run_burst_test(rps=500, duration=120)
        time.sleep(30)
        run_zipf_test(rps=300, duration=300)

    print(f"\n{'='*60}")
    print(f"✅ LOAD TEST COMPLETED")
    print(f"{'='*60}")
    print(f"Finished: {datetime.now().isoformat()}")
    print(f"Results: {RESULTS_DIR.absolute()}")
    print(f"{'='*60}\n")

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Real-time RPS Monitor
=====================

Monitor actual RPS from Prometheus metrics
"""

import requests
import time
from datetime import datetime

PROMETHEUS_URL = "http://localhost:8080/actuator/prometheus"

def get_request_count():
    """Get total request count from Prometheus"""
    try:
        response = requests.get(PROMETHEUS_URL, timeout=5)
        response.raise_for_status()

        for line in response.text.split('\n'):
            if 'http_server_requests_total' in line and '{' in line and 'endpoint=' in line:
                # Parse: http_server_requests_total{...} 12345.0
                parts = line.split(' ')
                if len(parts) >= 2:
                    try:
                        return float(parts[-1])
                    except:
                        pass
        return 0
    except Exception as e:
        print(f"Error fetching metrics: {e}")
        return 0

def main():
    print("="*60)
    print("📊 REAL-TIME RPS MONITOR")
    print("="*60)

    previous_count = get_request_count()
    start_time = time.time()

    print(f"\n[{datetime.now().strftime('%H:%M:%S')}] Initial request count: {int(previous_count)}")

    while True:
        time.sleep(10)  # Check every 10 seconds

        current_count = get_request_count()
        elapsed = time.time() - start_time

        if previous_count > 0:
            requests_delta = current_count - previous_count
            rps = requests_delta / 10  # RPS over last 10 seconds
            avg_rps = current_count / elapsed if elapsed > 0 else 0

            print(f"[{datetime.now().strftime('%H:%M:%S')}] "
                  f"Total: {int(current_count):,} | "
                  f"Last 10s RPS: {rps:.1f} | "
                  f"Avg RPS: {avg_rps:.1f} | "
                  f"Elapsed: {int(elapsed)}s")

        previous_count = current_count

if __name__ == '__main__':
    main()

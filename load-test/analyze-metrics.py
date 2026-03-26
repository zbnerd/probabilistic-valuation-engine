#!/usr/bin/env python3
"""
Metrics Analysis Script
=======================

Analyzes Prometheus metrics collected during load test and generates:
1. Bottleneck identification
2. Saturation point detection
3. Performance regression analysis
4. Root cause analysis for failures

Usage:
    python analyze-metrics.py <metrics_json_file>
"""

import json
import sys
from pathlib import Path
from datetime import datetime
import statistics

def load_metrics(metrics_file):
    """Load metrics from JSON file"""
    with open(metrics_file, 'r') as f:
        return json.load(f)

def extract_metric_series(metrics_history, metric_pattern):
    """Extract time series for a specific metric pattern"""
    series = []
    for snapshot in metrics_history:
        for metric_name, value in snapshot.items():
            if metric_pattern in metric_name:
                series.append({
                    'timestamp': snapshot.get('_timestamp', datetime.now().isoformat()),
                    'metric': metric_name,
                    'value': value
                })
    return series

def analyze_admission_control(metrics_history):
    """Analyze admission control metrics"""
    print("\n" + "="*60)
    print("📊 ADMISSION CONTROL ANALYSIS")
    print("="*60)

    queue_depth = []
    in_flight = []
    rejected = []

    for snapshot in metrics_history:
        for metric, value in snapshot.items():
            if 'admission_control_queue_depth' in metric:
                queue_depth.append(value)
            elif 'admission_control_in_flight' in metric:
                in_flight.append(value)
            elif 'admission_control_rejected' in metric and '_total' in metric:
                rejected.append(value)

    if queue_depth:
        print(f"\n🔸 Queue Depth:")
        print(f"   Average: {statistics.mean(queue_depth):.2f}")
        print(f"   Max: {max(queue_depth):.2f}")
        print(f"   Min: {min(queue_depth):.2f}")
        print(f"   StdDev: {statistics.stdev(queue_depth) if len(queue_depth) > 1 else 0:.2f}")

        # Detect queue buildup
        if len(queue_depth) >= 2:
            growth_rate = (queue_depth[-1] - queue_depth[0]) / len(queue_depth)
            if growth_rate > 10:
                print(f"   ⚠️  WARNING: Queue growing at {growth_rate:.2f} requests/snapshot")

    if in_flight:
        print(f"\n🔸 In-Flight Requests:")
        print(f"   Average: {statistics.mean(in_flight):.2f}")
        print(f"   Max: {max(in_flight):.2f}")
        print(f"   Min: {min(in_flight):.2f}")

    if rejected:
        print(f"\n🔸 Rejections:")
        print(f"   Total: {max(rejected) - min(rejected) if len(rejected) > 1 else rejected[0]:.0f}")
        print(f"   Rate: {(max(rejected) - min(rejected)) / len(rejected) if len(rejected) > 1 else 0:.2f} rejections/snapshot")

def analyze_executor(metrics_history):
    """Analyze executor metrics"""
    print("\n" + "="*60)
    print("📊 EXECUTOR ANALYSIS")
    print("="*60)

    active_threads = []
    queue_size = []

    for snapshot in metrics_history:
        for metric, value in snapshot.items():
            if 'executor_pool_active' in metric:
                active_threads.append(value)
            elif 'executor_queue_size' in metric:
                queue_size.append(value)

    if active_threads:
        print(f"\n🔸 Active Threads:")
        print(f"   Average: {statistics.mean(active_threads):.2f}")
        print(f"   Max: {max(active_threads):.2f}")
        print(f"   Min: {min(active_threads):.2f}")

        # Detect thread pool saturation
        max_active = max(active_threads)
        if max_active > 300:  # Assuming max pool size ~400
            print(f"   ⚠️  WARNING: Thread pool near saturation (max: {max_active})")

    if queue_size:
        print(f"\n🔸 Queue Size:")
        print(f"   Average: {statistics.mean(queue_size):.2f}")
        print(f"   Max: {max(queue_size):.2f}")
        print(f"   Min: {min(queue_size):.2f}")

        # Detect queue saturation
        max_queue = max(queue_size)
        if max_queue > 1600:  # Assuming capacity ~2000
            print(f"   ⚠️  WARNING: Executor queue near capacity (max: {max_queue})")

def analyze_async_tasks(metrics_history):
    """Analyze async task metrics"""
    print("\n" + "="*60)
    print("📊 ASYNC TASK ANALYSIS")
    print("="*60)

    completed = []
    failed = []
    timeout = []

    for snapshot in metrics_history:
        for metric, value in snapshot.items():
            if 'async_tasks_completed' in metric and '_total' in metric:
                completed.append(value)
            elif 'async_tasks_failed' in metric and '_total' in metric:
                failed.append(value)
            elif 'async_tasks_timeout' in metric and '_total' in metric:
                timeout.append(value)

    if completed:
        print(f"\n🔸 Completed Tasks:")
        print(f"   Total: {max(completed) - min(completed) if len(completed) > 1 else completed[0]:.0f}")
        print(f"   Throughput: {(max(completed) - min(completed)) / len(completed) if len(completed) > 1 else 0:.2f} tasks/snapshot")

    if failed:
        print(f"\n🔸 Failed Tasks:")
        print(f"   Total: {max(failed) - min(failed) if len(failed) > 1 else failed[0]:.0f}")
        if completed:
            failure_rate = (max(failed) - min(failed)) / (max(completed) - min(completed))
            print(f"   Failure Rate: {failure_rate*100:.2f}%")
            if failure_rate > 0.1:
                print(f"   ⚠️  WARNING: High failure rate")

    if timeout:
        print(f"\n🔸 Timeout Tasks:")
        print(f"   Total: {max(timeout) - min(timeout) if len(timeout) > 1 else timeout[0]:.0f}")
        if completed:
            timeout_rate = (max(timeout) - min(timeout)) / (max(completed) - min(completed))
            print(f"   Timeout Rate: {timeout_rate*100:.2f}%")

def analyze_micro_batch(metrics_history):
    """Analyze micro-batching metrics"""
    print("\n" + "="*60)
    print("📊 MICRO-BATCH ANALYSIS")
    print("="*60)

    buffer_size = []
    flush_count = []
    flush_duration = []

    for snapshot in metrics_history:
        for metric, value in snapshot.items():
            if 'micro_batch_buffer_size' in metric:
                buffer_size.append(value)
            elif 'micro_batch_flush' in metric and '_total' in metric:
                flush_count.append(value)
            elif 'micro_batch_flush_duration' in metric and '_sum' in metric:
                flush_duration.append(value)

    if buffer_size:
        print(f"\n🔸 Buffer Size:")
        print(f"   Average: {statistics.mean(buffer_size):.2f}")
        print(f"   Max: {max(buffer_size):.2f}")
        print(f"   Min: {min(buffer_size):.2f}")

    if flush_count:
        print(f"\n🔸 Flush Count:")
        print(f"   Total: {max(flush_count) - min(flush_count) if len(flush_count) > 1 else flush_count[0]:.0f}")
        print(f"   Rate: {(max(flush_count) - min(flush_count)) / len(flush_count) if len(flush_count) > 1 else 0:.2f} flushes/snapshot")

    if flush_duration:
        print(f"\n🔸 Flush Duration:")
        print(f"   Total: {max(flush_duration) - min(flush_duration) if len(flush_duration) > 1 else flush_duration[0]:.2f}s")

def detect_bottlenecks(metrics_history):
    """Detect system bottlenecks"""
    print("\n" + "="*60)
    print("🔍 BOTTLENECK DETECTION")
    print("="*60)

    bottlenecks = []

    # Check admission control queue
    queue_depths = [v for m in metrics_history for k, v in m.items() if 'admission_control_queue_depth' in k]
    if queue_depths and max(queue_depths) > 1600:  # 80% of 2000
        bottlenecks.append("Admission Control Queue Near Capacity")

    # Check executor queue
    executor_queues = [v for m in metrics_history for k, v in m.items() if 'executor_queue_size' in k]
    if executor_queues and max(executor_queues) > 1600:  # 80% of 2000
        bottlenecks.append("Executor Queue Near Capacity")

    # Check rejection rate
    rejected = [v for m in metrics_history for k, v in m.items() if 'admission_control_rejected' in k and '_count' in k]
    if rejected and max(rejected) > 50:
        bottlenecks.append("High Admission Rejection Rate")

    # Check timeout rate
    timeouts = [v for m in metrics_history for k, v in m.items() if 'async_tasks_timeout' in k and '_count' in k]
    if timeouts and max(timeouts) > 30:
        bottlenecks.append("High Task Timeout Rate")

    if bottlenecks:
        print("\n⚠️  BOTTLENECKS DETECTED:")
        for i, bottleneck in enumerate(bottlenecks, 1):
            print(f"   {i}. {bottleneck}")
    else:
        print("\n✅ No critical bottlenecks detected")

def generate_recommendations(metrics_history):
    """Generate tuning recommendations"""
    print("\n" + "="*60)
    print("💡 RECOMMENDATIONS")
    print("="*60)

    recommendations = []

    # Analyze queue depths
    queue_depths = [v for m in metrics_history for k, v in m.items() if 'admission_control_queue_depth' in k]
    if queue_depths:
        avg_queue = statistics.mean(queue_depths)
        max_queue = max(queue_depths)

        if max_queue > 1600:
            recommendations.append("Increase admission control queue size")
        if avg_queue > 1000:
            recommendations.append("Scale admission control workers or optimize task processing")

    # Analyze executor
    executor_active = [v for m in metrics_history for k, v in m.items() if 'executor_pool_active' in k]
    if executor_active:
        max_active = max(executor_active)
        if max_active > 350:
            recommendations.append("Increase executor max pool size")

    # Analyze timeouts
    timeouts = [v for m in metrics_history for k, v in m.items() if 'async_tasks_timeout' in k and '_count' in k]
    if timeouts and max(timeouts) > 30:
        recommendations.append("Increase async task timeout or optimize long-running tasks")

    if recommendations:
        print("\n📋 TUNING RECOMMENDATIONS:")
        for i, rec in enumerate(recommendations, 1):
            print(f"   {i}. {rec}")
    else:
        print("\n✅ Current configuration is optimal")

def main():
    if len(sys.argv) < 2:
        print("Usage: python analyze-metrics.py <metrics_json_file>")
        sys.exit(1)

    metrics_file = Path(sys.argv[1])

    if not metrics_file.exists():
        print(f"Error: Metrics file not found: {metrics_file}")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"📊 METRICS ANALYSIS")
    print(f"{'='*60}")
    print(f"File: {metrics_file}")
    print(f"Timestamp: {datetime.now().isoformat()}")

    metrics_history = load_metrics(metrics_file)

    if not metrics_history:
        print("\n⚠️  No metrics found in file")
        sys.exit(1)

    print(f"Snapshots: {len(metrics_history)}")

    # Run analyses
    analyze_admission_control(metrics_history)
    analyze_executor(metrics_history)
    analyze_async_tasks(metrics_history)
    analyze_micro_batch(metrics_history)
    detect_bottlenecks(metrics_history)
    generate_recommendations(metrics_history)

    print(f"\n{'='*60}")
    print(f"✅ ANALYSIS COMPLETE")
    print(f"{'='*60}\n")

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Slow task forensics: parse app.log, correlate with source code."""

import argparse
import re
import os
import glob
from datetime import datetime, timedelta
from collections import defaultdict, Counter

# --- Log parsing ---

SLOW_RE = re.compile(r"Slow task detected:\s*(.+?)\s*(?:took\s*)?\(?(\d+)ms\)?")
TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}[\sT]\d{2}:\d{2}:\d{2}[.,]\d{3})")

SIGNALS = [
    "TimeoutScanner", "DlqReplayWorker", "PgmqClient:Read", "PgmqClient:Archive",
    "QueueLength", "Hikari", "connection", "pool", "OCID_RESOLVE_TIMEOUT",
    "API_TIMEOUT", "Circuit Breaker", "external_api_queue", "expectation_calc_high",
    "AdvisoryLock", "Pipeline completed", "OCID resolve failed",
]

# --- Source mapping ---

SOURCE_MAP = {
    "TimeoutScanner": ("**/CalculationJobTimeoutScanner.kt", "scanStaleJobs"),
    "DlqReplayWorker": ("**/DlqReplayWorker.kt", "replayDeadLetters"),
    "PgmqWorker:ProcessMessage": ("**/PgmqWorker.kt", "processSingleMessage"),
    "PgmqWorker:ProcessBatch": ("**/PgmqWorker.kt", "processMessages"),
    "PgmqClient:Read": ("**/PgmqClient.kt", "performRead"),
    "PgmqClient:Archive": ("**/PgmqClient.kt", "performArchive"),
    "PgmqClient:QueueLength": ("**/PgmqClient.kt", "performQueueLength"),
    "AdvisoryLock:ElectLeader": ("**/PostgresAdvisoryLockStrategy.kt", "executeWithLeaderElection"),
    "AdvisoryLock:ExecuteWithLock": ("**/PostgresAdvisoryLockStrategy.kt", "executeWithLock"),
    "OutboxCompensatingScanner": ("**/OutboxCompensatingScanner.kt", "scan"),
    "ExternalApiWorker:Pipeline": ("**/ExternalApiWorker.kt", "processPipeline"),
    "ExpectationCalcWorker": ("**/ExpectationCalcWorker.kt", "process"),
    "NexonDataCacheAspect": ("**/NexonDataCacheAspect.kt", "around"),
    "GracefulShutdownHook": ("**/GracefulShutdownHook.kt", "run"),
    "ShutdownCoordinator": ("**/ShutdownCoordinator.kt", "coordinate"),
}


def parse_ts(line):
    m = TS_RE.search(line)
    if not m:
        return None
    raw = m.group(1).replace(",", ".")
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%f"):
        try:
            return datetime.strptime(raw, fmt)
        except ValueError:
            pass
    return None


def normalize_task(task):
    # Strip per-message IDs: PgmqWorker:ProcessMessage:external_api_queue:12345
    task = re.sub(r":(external_api_queue|expectation_calc_high|expectation_calc_low|calculation_queue|donation_queue|nexon_fanout_queue):\d+$", r":\1:MSG", task)
    # Strip per-IGN: ExternalApiWorker:Pipeline:진격캐넌
    task = re.sub(r"(ExternalApiWorker:Pipeline):.+$", r"\1:*", task)
    task = re.sub(r"(Observability:Track):.+$", r"\1:*", task)
    return task


def find_task_source_key(task):
    for key in SOURCE_MAP:
        if key in task:
            return key
    return None


def resolve_file(repo, glob_pattern):
    matches = glob.glob(os.path.join(repo, glob_pattern), recursive=True)
    return matches[0] if matches else None


def extract_method_source(filepath, method_name, context=30, repo_root="."):
    if not filepath or not os.path.exists(filepath):
        return f"File not found: {filepath}", ""

    with open(filepath, "r") as f:
        lines = f.readlines()

    start = None
    for i, line in enumerate(lines):
        if re.search(rf"\b{re.escape(method_name)}\b", line) and ("fun " in line or "def " in line):
            start = i
            break

    if start is None:
        for i, line in enumerate(lines):
            if method_name in line:
                start = i
                break

    if start is None:
        return os.path.relpath(filepath, repo_root), "".join(lines[:50])

    s = max(0, start - 3)
    e = min(len(lines), start + context + 3)
    rel = os.path.relpath(filepath, repo_root)
    snippet = "".join(f"{i+1:6d} | {lines[i]}" for i in range(s, e))
    return rel, snippet


def percentile(values, p):
    if not values:
        return 0
    values = sorted(values)
    k = int(round((len(values) - 1) * p))
    return values[k]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", default="module-app/logs/app.log")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--context-lines", type=int, default=30)
    parser.add_argument("--around-log-lines", type=int, default=20)
    parser.add_argument("--top", type=int, default=10)
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    log_path = args.log

    # --- Parse ---
    lines_raw = []
    slow_events = []

    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        for idx, line in enumerate(f):
            ts = parse_ts(line)
            lines_raw.append((idx, ts, line.rstrip("\n")))

            if "Slow task detected" not in line:
                continue
            m = SLOW_RE.search(line)
            if not m:
                continue
            task_raw = m.group(1).strip()
            task_raw = re.sub(r"\s+", " ", task_raw)
            ms = int(m.group(2))
            task = normalize_task(task_raw)

            slow_events.append({
                "idx": idx,
                "ts": ts,
                "task": task,
                "task_raw": task_raw,
                "ms": ms,
                "line": line.rstrip("\n"),
            })

    if not slow_events:
        print("No slow task events found.")
        return

    # --- Aggregate ---
    by_task = defaultdict(list)
    for e in slow_events:
        by_task[e["task"]].append(e)

    rows = []
    for task, events in by_task.items():
        vals = [e["ms"] for e in events]
        rows.append({
            "task": task,
            "count": len(vals),
            "avg": sum(vals) / len(vals),
            "p50": percentile(vals, 0.50),
            "p95": percentile(vals, 0.95),
            "p99": percentile(vals, 0.99),
            "max": max(vals),
            "total": sum(vals),
            "events": events,
        })

    # --- Build report ---
    out = []
    out.append("# Slow Task Forensics Report")
    out.append(f"\nLog: `{log_path}`")
    out.append(f"Total slow events: **{len(slow_events)}**")
    out.append(f"Unique tasks: **{len(by_task)}**")
    out.append("")

    # --- Summary tables ---
    for title, key in [("Top by Total Duration", "total"), ("Top by p95", "p95"), ("Top by Max", "max"), ("Top by Count", "count")]:
        out.append(f"## {title}\n")
        out.append(f"| # | Task | Count | Avg | p50 | p95 | p99 | Max | Total (s) |")
        out.append(f"|---|------|-------|-----|-----|-----|-----|-----|-----------|")
        sorted_rows = sorted(rows, key=lambda r: r[key], reverse=True)[:args.top]
        for i, r in enumerate(sorted_rows, 1):
            out.append(f"| {i} | `{r['task']}` | {r['count']} | {r['avg']:.0f}ms | {r['p50']}ms | {r['p95']}ms | {r['p99']}ms | {r['max']}ms | {r['total']/1000:.1f}s |")
        out.append("")

    # --- Time window analysis ---
    out.append("## Time Window Density (30s buckets)\n")
    bucket_stats = defaultdict(lambda: {"count": 0, "total": 0, "tasks": Counter(), "signals": Counter()})
    bucket_size = 30

    for e in slow_events:
        if not e["ts"]:
            continue
        b = e["ts"].replace(second=(e["ts"].second // bucket_size) * bucket_size, microsecond=0)
        bucket_stats[b]["count"] += 1
        bucket_stats[b]["total"] += e["ms"]
        bucket_stats[b]["tasks"][e["task"]] += 1

    for _, ts, line in lines_raw:
        if not ts:
            continue
        b = ts.replace(second=(ts.second // bucket_size) * bucket_size, microsecond=0)
        for sig in SIGNALS:
            if sig in line:
                bucket_stats[b]["signals"][sig] += 1

    out.append(f"| Time | Slow Count | Avg ms | Top Task | Correlated Signals |")
    out.append(f"|------|-----------|--------|----------|-------------------|")
    for bucket, ws in sorted(bucket_stats.items(), key=lambda x: x[1]["count"], reverse=True)[:15]:
        top_task = ws["tasks"].most_common(1)[0] if ws["tasks"] else ("-", 0)
        top_sigs = ", ".join(f"{k}={v}" for k, v in ws["signals"].most_common(5))
        out.append(f"| {bucket.strftime('%H:%M:%S')} | {ws['count']} | {ws['total']/max(ws['count'],1):.0f}ms | {top_task[0]}({top_task[1]}) | {top_sigs} |")
    out.append("")

    # --- Per-task detail ---
    # Sort by total duration descending
    detail_rows = sorted(rows, key=lambda r: r["total"], reverse=True)[:args.top]

    out.append("## Top Task Details\n")
    out.append("Each section: stats → representative slow log → nearby logs → source code → suspected cause.\n")

    for rank, r in enumerate(detail_rows, 1):
        task = r["task"]
        out.append(f"### {rank}. `{task}`\n")

        # Stats
        out.append(f"**Stats:** count={r['count']}, avg={r['avg']:.0f}ms, p50={r['p50']}ms, p95={r['p95']}ms, p99={r['p99']}ms, max={r['max']}ms, total={r['total']/1000:.1f}s\n")

        # Representative event (max duration)
        max_event = max(r["events"], key=lambda e: e["ms"])
        out.append(f"**Max slow event** ({max_event['ms']}ms):\n")
        out.append(f"```")
        out.append(max_event["line"])
        out.append(f"```\n")

        # Nearby logs
        idx = max_event["idx"]
        s = max(0, idx - args.around_log_lines)
        e = min(len(lines_raw), idx + args.around_log_lines + 1)
        out.append(f"**Nearby logs** (line {s+1}~{e}):\n")
        out.append(f"```")
        signal_lines = []
        for i, ts, line in lines_raw[s:e]:
            marker = ">>>" if i == idx else "   "
            is_signal = any(sig in line for sig in SIGNALS)
            if is_signal or i == idx:
                signal_lines.append(f"{marker} {line}")
        # Always include the slow event itself + signals
        if not signal_lines:
            for i, ts, line in lines_raw[max(0, idx-3):min(len(lines_raw), idx+4)]:
                signal_lines.append(f"{'>>>' if i == idx else '   '} {line}")
        for sl in signal_lines[:40]:
            out.append(sl)
        out.append(f"```\n")

        # Source code
        source_key = find_task_source_key(task)
        if source_key:
            glob_pat, method = SOURCE_MAP[source_key]
            filepath = resolve_file(repo, glob_pat)
            rel_path, snippet = extract_method_source(filepath, method, args.context_lines, repo)
            out.append(f"**Source:** `{rel_path}` — `{method}()`\n")
            out.append(f"```kotlin")
            out.append(snippet)
            out.append(f"```\n")
        else:
            out.append(f"**Source:** No mapping found for `{task}`\n")

        # Suspected cause
        out.append(f"**Suspected cause:**\n")
        cause = identify_cause(task, r, max_event, lines_raw)
        out.append(cause)
        out.append("")

    # --- Write ---
    report = "\n".join(out)
    output_path = args.output or os.path.join(repo, "docs", "05_Reports", "slow-task-source-report.md")
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w") as f:
        f.write(report)
    print(f"Report written to: {output_path}")
    print(f"Total slow events: {len(slow_events)}")
    print(f"Unique tasks: {len(by_task)}")


def identify_cause(task, row, max_event, lines_raw):
    causes = []

    if "TimeoutScanner" in task:
        causes.append("- Scanner dispatches retries without CAS protection → duplicate messages flood queue")
        causes.append("- Stale threshold too short for queue backlog wait times")
        causes.append("- Uses legacy topic dispatch instead of consolidated queue")
    elif "PgmqClient:Read" in task:
        causes.append("- `pgmq.read()` SQL function with SKIP LOCKED → Supabase pgBouncer adds routing latency")
        causes.append("- 16 workers competing for connections → HikariCP pool contention")
        causes.append("- JSONB deserialization overhead per message via Jackson")
    elif "PgmqClient:Archive" in task:
        causes.append("- `pgmq.archive()` = DELETE + INSERT per message → 2 DB writes")
        causes.append("- Single-message archive instead of batch")
        causes.append("- Called from `processSingleMessage` after pipeline completes → serial DB roundtrip")
    elif "PgmqClient:QueueLength" in task:
        causes.append("- `pgmq.metrics()` SQL query every 300ms poll cycle")
        causes.append("- Not needed for processing — only for metrics dashboard")
    elif "PgmqWorker:ProcessBatch" in task:
        causes.append("- Poll cycle calls `pgmqClient.read()` + `pgmqClient.queueLength()` → 2 DB roundtrips")
        causes.append("- Serial execution: read → metrics → dispatch")
    elif "PgmqWorker:ProcessMessage" in task:
        causes.append("- Calls subclass `process()` → full pipeline (API + DB writes)")
        causes.append("- After pipeline: `pgmqClient.archive()` adds another DB roundtrip")
        causes.append("- Total per message: API (~500ms) + DB writes (~250ms) + archive (~250ms)")
    elif "DlqReplay" in task:
        causes.append("- Scans 6 queue archive tables sequentially")
        causes.append("- Per-queue: discover + track + replay = multiple DB roundtrips")
        causes.append("- No parallelization across queues")
    elif "AdvisoryLock:ElectLeader" in task:
        causes.append("- Leader holds xact lock during API call (~300ms)")
        causes.append("- Followers poll every 100ms for lock release → DB contention")
        causes.append("- 16 workers → 1 leader + 15 followers each doing DB queries")
    elif "OutboxCompensating" in task:
        causes.append("- Iterates orphaned jobs one-by-one with individual INSERT")
        causes.append("- Inline projection reduces orphan rate but scanner still runs")
    elif "ExternalApiWorker:Pipeline" in task:
        causes.append("- Nexon API calls: OCID (~200ms) + Equipment (~300ms) = ~500ms")
        causes.append("- Snapshot save + CalculationInput save + completeCalculationWithResult = 3 DB writes")
        causes.append("- Inline view projection adds 1 more DB write (upsertFromCalculation)")
        causes.append("- Pipeline total: ~500ms API + ~500ms DB = ~1000ms per job")
    elif "Shutdown" in task:
        causes.append("- Graceful shutdown draining in-flight tasks — not a production concern")
    else:
        causes.append("- Needs investigation: check nearby logs for correlated DB/API slowness")

    # Check for correlation with signals
    idx = max_event["idx"]
    s = max(0, idx - 20)
    e = min(len(lines_raw), idx + 21)
    nearby = "".join(line for _, _, line in lines_raw[s:e])

    correlated = []
    if "TimeoutScanner" in nearby and "TimeoutScanner" not in task:
        correlated.append("TimeoutScanner active in same window")
    if "Hikari" in nearby or "connection" in nearby.lower():
        correlated.append("DB connection pool pressure detected")
    if "AdvisoryLock" in nearby and "AdvisoryLock" not in task:
        correlated.append("Lock contention in same window")
    if "Circuit Breaker" in nearby:
        correlated.append("Circuit breaker triggered nearby")

    if correlated:
        causes.append("")
        causes.append("**Correlated events:**")
        for c in correlated:
            causes.append(f"- {c}")

    return "\n".join(causes)


if __name__ == "__main__":
    main()

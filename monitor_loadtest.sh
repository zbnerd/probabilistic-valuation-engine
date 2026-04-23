#!/bin/bash
# Load test monitoring script
# Monitors: JVM GC, HikariCP, PGMQ drain rate, server exceptions

source .env
DB_ARGS=("-h" "$DB_SERVER_IP" "-U" "maple" "-d" "maple_expectation")
LOGDIR="/tmp/loadtest-monitor-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOGDIR"

echo "=== Load Test Monitor ==="
echo "Logs: $LOGDIR"
echo "Started at: $(date)"

# 1. JVM GC monitoring via actuator
monitor_gc() {
    local outfile="$LOGDIR/gc_stats.log"
    echo "timestamp,young_gc_count,young_gc_time_ms,old_gc_count,old_gc_time_ms,heap_used_mb,heap_max_mb,old_gen_used_mb,old_gen_max_mb" > "$outfile"
    while true; do
        local data=$(curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause 2>/dev/null)
        if [ -z "$data" ]; then sleep 2; continue; fi

        local ts=$(date +%s)
        local young_count=$(echo "$data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='COUNT']
print(m[0]['value'] if m else 0)" 2>/dev/null)
        local young_time=$(echo "$data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='TOTAL_TIME']
print(m[0]['value'] if m else 0)" 2>/dev/null)

        # Heap usage
        local heap_data=$(curl -s http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap 2>/dev/null)
        local heap_used=$(echo "$heap_data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']
print(int(m[0]['value']/1048576) if m else 0)" 2>/dev/null)

        local heap_max_data=$(curl -s http://localhost:8080/actuator/metrics/jvm.memory.max?tag=area:heap 2>/dev/null)
        local heap_max=$(echo "$heap_max_data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']
print(int(m[0]['value']/1048576) if m else 0)" 2>/dev/null)

        # Old Gen
        local old_data=$(curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=id:G1%20Old%20Gen" 2>/dev/null)
        local old_used=$(echo "$old_data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']
print(int(m[0]['value']/1048576) if m else 0)" 2>/dev/null)

        local old_max_data=$(curl -s "http://localhost:8080/actuator/metrics/jvm.memory.max?tag=id:G1%20Old%20Gen" 2>/dev/null)
        local old_max=$(echo "$old_max_data" | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']
print(int(m[0]['value']/1048576) if m else 0)" 2>/dev/null)

        echo "$ts,$young_count,${young_time}000,$old_gc_count,${old_gc_time}000,$heap_used,$heap_max,$old_used,$old_max" >> "$outfile"
        sleep 2
    done
}

# 2. HikariCP connection pool monitoring
monitor_hikari() {
    local outfile="$LOGDIR/hikari_stats.log"
    echo "timestamp,active,idle,pending,max,total,acquire_max_ms" > "$outfile"
    while true; do
        local data=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active 2>/dev/null)
        if [ -z "$data" ]; then sleep 2; continue; fi

        local ts=$(date +%s)
        local active=$(echo "$data" | python3 -c "import sys,json; d=json.load(sys.stdin); m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']; print(int(m[0]['value']) if m else 0)" 2>/dev/null)

        local idle=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.idle 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']; print(int(m[0]['value']) if m else 0)" 2>/dev/null)

        local pending=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']; print(int(m[0]['value']) if m else 0)" 2>/dev/null)

        local max=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.max 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']; print(int(m[0]['value']) if m else 0)" 2>/dev/null)

        local total=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); m=[x for x in d.get('measurements',[]) if x.get('statistic')=='VALUE']; print(int(m[0]['value']) if m else 0)" 2>/dev/null)

        local acquire=$(curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.acquire.seconds 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
m=[x for x in d.get('measurements',[]) if x.get('statistic')=='MAX']
print(int((m[0]['value'] if m else 0)*1000))" 2>/dev/null)

        echo "$ts,$active,$idle,$pending,$max,$total,$acquire" >> "$outfile"
        sleep 2
    done
}

# 3. PGMQ drain rate monitoring
monitor_pgmq() {
    local outfile="$LOGDIR/pgmq_drain.log"
    echo "timestamp,queue_high,archive_total,views_count,drain_rate_per_sec" > "$outfile"
    local prev_archive=0
    local prev_ts=0
    while true; do
        local ts=$(date +%s)
        local counts=$(PGPASSWORD="$DB_ROOT_PASSWORD" psql "${DB_ARGS[@]}" -t -c "
            SELECT
                (SELECT count(*) FROM pgmq.q_expectation_calc_high),
                (SELECT count(*) FROM pgmq.a_expectation_calc_high),
                (SELECT count(*) FROM character_valuation_views);
        " 2>/dev/null | tr -d ' ')

        if [ -z "$counts" ]; then sleep 2; continue; fi

        local q_high=$(echo "$counts" | awk -F'|' '{print $1}' | tr -d ' ')
        local archive=$(echo "$counts" | awk -F'|' '{print $2}' | tr -d ' ')
        local views=$(echo "$counts" | awk -F'|' '{print $3}' | tr -d ' ')

        local drain_rate=0
        if [ "$prev_ts" -ne 0 ]; then
            local dt=$((ts - prev_ts))
            if [ "$dt" -gt 0 ]; then
                drain_rate=$(echo "scale=1; ($archive - $prev_archive) / $dt" | bc 2>/dev/null)
            fi
        fi

        echo "$ts,$q_high,$archive,$views,$drain_rate" >> "$outfile"
        prev_archive=$archive
        prev_ts=$ts
        sleep 3
    done
}

# Start all monitors
monitor_gc &
GC_PID=$!
monitor_hikari &
HIKARI_PID=$!
monitor_pgmq &
PGMQ_PID=$!

echo "Monitor PIDs: GC=$GC_PID, HikariCP=$HIKARI_PID, PGMQ=$PGMQ_PID"
echo "Logs directory: $LOGDIR"
echo ""
echo "To stop monitors: kill $GC_PID $HIKARI_PID $PGMQ_PID"
echo "To view live GC: tail -f $LOGDIR/gc_stats.log"
echo "To view live HikariCP: tail -f $LOGDIR/hikari_stats.log"
echo "To view live PGMQ: tail -f $LOGDIR/pgmq_drain.log"

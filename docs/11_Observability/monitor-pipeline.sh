#!/usr/bin/env bash
# Pipeline monitoring — disk-based totals + tac for current rate
set -uo pipefail

CALC_LOG="/tmp/calculator-bootrun.log"
EXT_LOG="/tmp/external-api-bootrun.log"
CALC_PID=$(pgrep -f 'java.*module-calculator/build/classes' | head -1)
EXT_PID=$(pgrep -f 'java.*module-external-api/build/classes' | head -1)
DATA_DIR="module-external-api/external-api-data"

echo "=== Pipeline Monitor $(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST') ==="

# --- 서버 시작 시간 (KST) ---
echo ""
if [ -n "$CALC_PID" ]; then
  START_EPOCH=$(ps -p $CALC_PID -o lstart= | xargs -I{} date -d "{}" +%s 2>/dev/null)
  START_KST=$(TZ=Asia/Seoul date -d "@$START_EPOCH" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
  ELAPSED=$(ps -p $CALC_PID -o etime= | sed 's/^ *//')
  echo "서버 시작(KST): $START_KST  가동시간: $ELAPSED"
fi

# --- Resources ---
echo ""
for PID in $EXT_PID $CALC_PID; do
  [ -z "$PID" ] && continue
  NAME=$(ps -p $PID -o args= | grep -oP 'module-\w+' | head -1)
  CPU=$(ps -p $PID -o %cpu= | tr -d ' ')
  RSS=$(ps -p $PID -o rss= | tr -d ' ')
  RAM_MB=$((RSS / 1024))

  HEAP_INFO=$(jcmd $PID GC.heap_info 2>/dev/null | sed -n '2p')
  HEAP_USED_K=$(echo "$HEAP_INFO" | grep -oP 'used\s+\K\d+' || echo 0)
  HEAP_TOTAL_K=$(echo "$HEAP_INFO" | grep -oP 'total\s+\K\d+' || echo 1)
  HEAP_MB=$((HEAP_USED_K / 1024))
  HEAP_TOTAL_MB=$((HEAP_TOTAL_K / 1024))
  HEAP_PCT=$(awk "BEGIN{printf \"%.0f\", ${HEAP_USED_K}*100/${HEAP_TOTAL_K}}" 2>/dev/null)

  GC_LINE=$(jstat -gcutil $PID 2>/dev/null | tail -1)
  OLD_PCT=$(echo "$GC_LINE" | awk '{print $4}')

  echo "$NAME: CPU=${CPU}% RAM=${RAM_MB}MB Heap=${HEAP_MB}MB/${HEAP_TOTAL_MB}MB(${HEAP_PCT}%) Old=${OLD_PCT}%"
done

# --- Calculator 총합 (disk 기반, 이번 세션만) ---
echo ""
echo "--- Calculator ---"
# 서버 시작 epoch → runId 필터 기준
SESSION_START_EPOCH=$START_EPOCH
SESSION_START_RUN=$(TZ=Asia/Seoul date -d "@$SESSION_START_EPOCH" '+%Y%m%d-%H%M%S' 2>/dev/null)
SESSION_CHUNKS=0
SESSION_CALC_RUNS=0
for RUNDIR in "$DATA_DIR"/data/calculator/runs/*/; do
  [ -d "$RUNDIR" ] || continue
  RUN=$(basename "$RUNDIR")
  if [[ "$RUN" > "$SESSION_START_RUN" || "$RUN" == "$SESSION_START_RUN" ]]; then
    CNT=$(find "$RUNDIR" -name 'result-part-*.jsonl.gz' 2>/dev/null | wc -l)
    SESSION_CHUNKS=$((SESSION_CHUNKS + CNT))
    SESSION_CALC_RUNS=$((SESSION_CALC_RUNS + 1))
  fi
done
SESSION_USERS=$((SESSION_CHUNKS * 500))
SESSION_ITEMS=$((SESSION_CHUNKS * 35000))

# 초당 처리량 (가동시간 기준)
if [ -n "$CALC_PID" ]; then
  ELAPSED_STR=$(ps -p $CALC_PID -o etime= | sed 's/^ *//')
  if echo "$ELAPSED_STR" | grep -q '-'; then
    DAYS=$(echo "$ELAPSED_STR" | cut -d- -f1)
    REST=$(echo "$ELAPSED_STR" | cut -d- -f2)
    ELAPSED_SEC=$((DAYS * 86400 + $(echo $REST | tr ':' ' ' | awk '{print $1*3600+$2*60+$3}')))
  else
    ELAPSED_SEC=$(echo "$ELAPSED_STR" | tr ':' ' ' | awk '{print $1*3600+$2*60+$3}')
  fi
  AVG_RATE=$((SESSION_USERS / ELAPSED_SEC))
  echo "  [이번 세션] runs=$SESSION_CALC_RUNS chunks=$SESSION_CHUNKS users≈$(printf "%'d" $SESSION_USERS) items≈$(printf "%'d" $SESSION_ITEMS) avg_rate=${AVG_RATE}/s"
fi

# --- Calculator 현재 run 속도 (tac, 마지막 20개만) ---
tac "$CALC_LOG" 2>/dev/null | grep -m 20 'processed chunk' | tac | awk '
{
  for(i=1;i<=NF;i++) {
    if($i ~ /^runId=/) run=substr($i,7)
    if($i ~ /^records=/) rec=substr($i,9)+0
    if($i ~ /^items=/) itm=substr($i,7)+0
    if($i ~ /^errors=/) err=substr($i,8)+0
  }
  split($1,t,":"); sec=t[1]*3600+t[2]*60+t[3]
  if(run != prev_run && prev_run != "") {
    el = prev_last_sec >= prev_first_sec ? prev_last_sec - prev_first_sec : prev_last_sec + 86400 - prev_first_sec
    r = el>0 ? prev_chunks*500/el : 0
    prev_chunks=0
  }
  if(run != prev_run) { prev_run=run; prev_first_sec=sec }
  prev_last_sec=sec
  prev_chunks++
  last_run=run; last_time=$1
}
END {
  el = prev_last_sec >= prev_first_sec ? prev_last_sec - prev_first_sec : prev_last_sec + 86400 - prev_first_sec
  r = el>0 ? prev_chunks*500/el : 0
  printf "  [실시간] run=%s rate=%d/s (최근 %d chunks)\n", last_run, r, prev_chunks
}'

# --- External-API ---
echo ""
echo "--- External-API ---"
tac "$EXT_LOG" 2>/dev/null | grep -m 1 '\[Scheduler\]\|\[Event\] published chunk' | sed 's/.*INFO  /  /'

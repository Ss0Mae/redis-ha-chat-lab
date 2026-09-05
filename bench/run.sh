#!/usr/bin/env bash
# 실험 1회 실행. 앱이 이미 해당 토폴로지로 떠 있어야 한다.
#   bench/run.sh <experiment-set> <scenario> [rps] [rep]
# env: DRIVER=app|k6 (기본 app: 앱 내부 워크로드, k6: HTTP), WARM/STEADY/FAULT/RECOVER(초), TARGET, DELAY_MS, LOSS_PCT,
#      RECORDING=MEMORY|BATCH|SAMPLED, REDIS_SETTINGS(JSON 객체 문자열), USERS, ROOMS, NAME, HYPOTHESIS
set -euo pipefail
cd "$(dirname "$0")/.."
SET=$1; SCENARIO=$2; RPS=${3:-500}; REP=${4:-1}
DRIVER=${DRIVER:-app}; WARM=${WARM:-60}; STEADY=${STEADY:-120}; FAULT=${FAULT:-120}; RECOVER=${RECOVER:-120}
RECORDING=${RECORDING:-BATCH}; USERS=${USERS:-10000}; ROOMS=${ROOMS:-100}
bench/wait-stable.sh 90 || { echo "topology not stable; aborting run"; exit 1; }
TOPO=$(bench/api.sh GET /api/redis/topology | python3 -c "import sys,json;print(json.load(sys.stdin)['topology'])")
NAME=${NAME:-"$SET/$SCENARIO/$TOPO/rps$RPS/rep$REP"}
mkdir -p "results/$SET/raw"
WL="null"; [ "$DRIVER" = "app" ] && WL="{\"rps\":$RPS,\"users\":$USERS,\"rooms\":$ROOMS}"
RS=${REDIS_SETTINGS:-}; [ -z "$RS" ] && RS='{}'
BODY=$(python3 - "$NAME" "$RECORDING" "$WL" "$RS" "${HYPOTHESIS:-}" <<'PY'
import sys,json
name,rec,wl,rs,hyp=sys.argv[1:6]
print(json.dumps({"name":name,"hypothesis":hyp,"recordingMode":rec,"workload":json.loads(wl),"redisSettings":json.loads(rs),"startWorkload":wl!="null","flush":True}))
PY
)
E=$(bench/api.sh POST /api/experiments "$BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id') or sys.exit('create failed: '+str(d)))")
echo "[$(date +%T)] experiment $E ($NAME) driver=$DRIVER"
START_TS=$(date +%s)
# 자원 사용량 표본(docker stats, 5 s 간격) — 앱·Redis·Sentinel 컨테이너 CPU/메모리/네트워크
( while true; do TS=$(date +%s); docker stats --no-stream --format '{{json .}}' $(docker ps --format '{{.Names}}' | grep -E '^(lab-app|r-a-[1-3]|r-0|c-[1-6]|s-[1-3])$' | tr '\n' ' ') 2>/dev/null | sed "s/^{/{\"ts\":$TS,/" >> "results/$SET/raw/$E.stats.jsonl"; sleep 5; done ) &
STATS_PID=$!
K6PID=""
if [ "$DRIVER" = "k6" ]; then
  RPS=$RPS USERS=$USERS ROOMS=$ROOMS WARM=$WARM STEADY=$STEADY FAULT=$FAULT RECOVER=$RECOVER OUT="results/$SET/raw/$E.k6.json" \
    k6 run --quiet k6/chat.js > "results/$SET/raw/$E.k6.log" 2>&1 &
  K6PID=$!
fi
bench/api.sh POST "/api/experiments/$E/phase" '{"phase":"WARMUP"}' >/dev/null
sleep "$WARM"
bench/api.sh POST "/api/experiments/$E/phase" '{"phase":"BEFORE"}' >/dev/null
sleep "$STEADY"
FAULT_BODY=$(python3 - "$SCENARIO" "${TARGET:-}" "${DELAY_MS:-}" "${LOSS_PCT:-}" <<'PY'
import sys,json
s,t,d,l=sys.argv[1:5]
b={"scenario":s,"confirm":True}
if t: b["target"]=t
if d: b["delayMs"]=int(d)
if l: b["lossPct"]=int(l)
print(json.dumps(b))
PY
)
echo "[$(date +%T)] inject $SCENARIO"
bench/api.sh POST "/api/experiments/$E/inject-failure" "$FAULT_BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  failedNode',d.get('failedNode'),d.get('error',''));sys.exit(1 if d.get('error') else 0)" || { echo "inject failed"; exit 1; }
sleep "$FAULT"
echo "[$(date +%T)] recover"
bench/api.sh POST "/api/experiments/$E/recover" >/dev/null
sleep "$RECOVER"
[ -n "$K6PID" ] && wait "$K6PID" || true
echo "[$(date +%T)] finish"
kill $STATS_PID 2>/dev/null || true; wait $STATS_PID 2>/dev/null || true
python3 bench/promstats.py "$START_TS" "$(date +%s)" > "results/$SET/raw/$E.prom.json" 2>/dev/null || true
bench/api.sh POST "/api/experiments/$E/finish" > "results/$SET/raw/$E.experiment.json"
bench/api.sh GET "/api/experiments/$E/samples" > "results/$SET/raw/$E.samples.json"
python3 bench/collect.py "$SET" "$E" "$SCENARIO" "$RPS" "$REP" "$DRIVER" >> "results/$SET/runs.jsonl"
python3 - "results/$SET/raw/$E.experiment.json" <<'PY'
import sys,json
d=json.load(open(sys.argv[1])); t=d['summary'].get('timings',{}); c=d['summary'].get('consistency',{}); p=d['summary'].get('phases',{})
print(f"  outage {t.get('outage_ms')} ms, detect {t.get('detect_ms')}, promote {t.get('promote_ms')}, stabilize {t.get('stabilize_ms')}, rejoin {t.get('rejoin_ms')} | lost {c.get('lost_acked_writes')} dupINCR {c.get('duplicate_incr')} | during err {p.get('DURING',{}).get('error_rate_pct')}%")
PY

#!/usr/bin/env bash
# 기록 방식 비교(장애 없음), Sentinel 경로 Toxiproxy 실험. results/misc/
set -uo pipefail
cd "$(dirname "$0")/.."
REPS=${REPS:-3}
echo "[$(date +%T)] === recording modes (sentinel, no fault) ==="
bench/switch.sh sentinel
for rep in $(seq 1 "$REPS"); do
  for mode in MEMORY BATCH SAMPLED; do
    E=$(bench/api.sh POST /api/experiments "{\"name\":\"misc/recording-$mode/NONE/sentinel/rps1000/rep$rep\",\"recordingMode\":\"$mode\",\"workload\":{\"rps\":1000,\"users\":10000,\"rooms\":100},\"startWorkload\":true}" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
    bench/api.sh POST "/api/experiments/$E/phase" '{"phase":"WARMUP"}' >/dev/null; sleep 15
    bench/api.sh POST "/api/experiments/$E/phase" '{"phase":"BEFORE"}' >/dev/null; sleep 60
    mkdir -p results/misc/raw
    bench/api.sh POST "/api/experiments/$E/finish" > "results/misc/raw/$E.experiment.json"
    bench/api.sh GET "/api/experiments/$E/samples" > "results/misc/raw/$E.samples.json"
    # 앱 CPU(Prometheus process_cpu_usage 평균, 최근 60 s)
    CPU=$(curl -s "http://localhost:9093/api/v1/query?query=avg_over_time(process_cpu_usage%5B60s%5D)" | python3 -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print(r[0]['value'][1] if r else '')")
    python3 bench/collect.py misc "$E" "NONE" 1000 "$rep" app | python3 -c "import sys,json;r=json.loads(sys.stdin.read());r['variant']='recording-$mode';r['app_cpu']=float('$CPU' or 0);print(json.dumps(r,ensure_ascii=False))" >> results/misc/runs.jsonl
    echo "[$(date +%T)] recording $mode rep $rep done (cpu $CPU)"; sleep 5
  done
done
echo "[$(date +%T)] === sentinel-toxi: 앱↔Sentinel 경로 장애 ==="
bench/switch.sh sentinel-toxi
export WARM=10 STEADY=30 FAULT=40 RECOVER=40 RPS=300
for rep in $(seq 1 "$REPS"); do
  for sc in TOXIC_APP_SENTINEL_TIMEOUT TOXIC_APP_SENTINEL_LATENCY; do
    export DELAY_MS=""; [ "$sc" = TOXIC_APP_SENTINEL_LATENCY ] && export DELAY_MS=300
    NAME="misc/$sc/$sc/sentinel-toxi/rps300/rep$rep" bench/run.sh misc "$sc" 300 "$rep" || true; sleep 5
  done
done
bench/switch.sh sentinel
echo "[$(date +%T)] === all-misc done ==="

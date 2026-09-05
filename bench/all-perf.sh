#!/usr/bin/env bash
# 동일 부하 성능 비교(k6, HTTP): 100/500/1000/2000 RPS × 5회 × Sentinel/Cluster. 각 실행 7분(1+2+2+2).
set -uo pipefail
cd "$(dirname "$0")/.."
export DRIVER=k6 WARM=60 STEADY=120 FAULT=120 RECOVER=120 RECORDING=MEMORY
REPS=${REPS:-5}
for topo in cluster sentinel; do
  echo "[$(date +%T)] === perf $topo ==="
  bench/switch.sh "$topo"
  for rep in $(seq 1 "$REPS"); do
    for rps in 100 500 1000 2000; do
      bench/run.sh perf KILL_PRIMARY "$rps" "$rep" || { A=$(bench/api.sh GET /api/experiments/active | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))"); [ -n "$A" ] && { bench/api.sh POST "/api/experiments/$A/recover" >/dev/null; sleep 20; bench/api.sh POST "/api/experiments/$A/finish" >/dev/null; }; }
      sleep 10
    done
  done
done
echo "[$(date +%T)] === all-perf done ==="

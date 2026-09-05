#!/usr/bin/env bash
# 오염된 Sentinel 실행 재실행 → faults 재집계 → 옵션/설정/민감도 배치
set -uo pipefail
cd "$(dirname "$0")/.."
export RPS=300 WARM=10 STEADY=30 FAULT=40 RECOVER=40
echo "[$(date +%T)] === rerun contaminated sentinel runs ==="
bench/wait-stable.sh 120
for rep in 2 4 5; do
  for sc in STOP_SENTINELS_THEN_KILL_PRIMARY STOP_ALL_SENTINELS NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY; do
    export DELAY_MS=""; [ "$sc" = NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY ] && export DELAY_MS=500
    bench/run.sh faults "$sc" 300 "$rep" || { A=$(bench/api.sh GET /api/experiments/active | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))"); [ -n "$A" ] && { bench/api.sh POST "/api/experiments/$A/recover" >/dev/null; sleep 20; bench/api.sh POST "/api/experiments/$A/finish" >/dev/null; }; }
    sleep 5
  done
done
python3 bench/recollect.py faults
echo "[$(date +%T)] === options batch ==="
bench/all-options.sh
echo "[$(date +%T)] === chain-2 done ==="

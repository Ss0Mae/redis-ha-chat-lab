#!/usr/bin/env bash
# 장애 시나리오 매트릭스를 반복 실행한다. 앱이 해당 토폴로지로 떠 있어야 한다.
#   bench/batch.sh <set> <reps> <scenario...>
# env: RPS(기본 300), WARM/STEADY/FAULT/RECOVER(기본 10/30/40/40), 그 외 run.sh 와 같음
set -uo pipefail
cd "$(dirname "$0")/.."
SET=$1; REPS=$2; shift 2
export WARM=${WARM:-10} STEADY=${STEADY:-30} FAULT=${FAULT:-40} RECOVER=${RECOVER:-40}
RPS=${RPS:-300}
for rep in $(seq 1 "$REPS"); do
  for sc in "$@"; do
    # 시나리오별 파라미터
    export DELAY_MS="" LOSS_PCT=""
    case $sc in
      NETEM_GLOBAL_DELAY) export DELAY_MS=200 ;;
      NETEM_LOSS) export LOSS_PCT=5 ;;
      NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY) export DELAY_MS=${REPL_DELAY_MS:-500} ;;
    esac
    for attempt in 1 2; do
      if bench/run.sh "$SET" "$sc" "$RPS" "$rep"; then break; fi
      echo "[$(date +%T)] run failed ($sc rep $rep attempt $attempt); restoring and retrying"
      A=$(bench/api.sh GET /api/experiments/active | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
      [ -n "$A" ] && { bench/api.sh POST "/api/experiments/$A/recover" >/dev/null; sleep 20; bench/api.sh POST "/api/experiments/$A/finish" >/dev/null; }
      sleep 10
    done
    sleep 5
  done
done
echo "[$(date +%T)] batch $SET done"

#!/usr/bin/env bash
# 전체 실험 체인: Sentinel 장애 매트릭스(설정 영속화 버전) → 옵션/설정/민감도 → k6 성능 → 기타
set -uo pipefail
cd "$(dirname "$0")/.."
export RPS=300 WARM=10 STEADY=30 FAULT=40 RECOVER=40
SENTINEL="KILL_PRIMARY STOP_PRIMARY PAUSE_PRIMARY PARTITION_PRIMARY_FROM_SENTINELS PARTITION_PRIMARY_FROM_REPLICAS PARTITION_PRIMARY_FROM_APP NETEM_GLOBAL_DELAY NETEM_LOSS KILL_REPLICA_THEN_PRIMARY KILL_ALL_REPLICAS_THEN_PRIMARY STOP_SENTINELS_THEN_KILL_PRIMARY STOP_ALL_SENTINELS NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY"
echo "[$(date +%T)] === sentinel faults (persisted config) ==="
bench/wait-stable.sh 120
bench/batch.sh faults 5 $SENTINEL
echo "[$(date +%T)] === options ==="
unset RPS WARM STEADY FAULT RECOVER
bench/all-options.sh
echo "[$(date +%T)] === perf ==="
bench/all-perf.sh
echo "[$(date +%T)] === misc ==="
bench/all-misc.sh
echo "[$(date +%T)] === chain-3 done ==="

#!/usr/bin/env bash
# 후속 변형: adaptive 단독이 무력했던 원인(adaptiveRefreshTriggersTimeout 30 s 쿨다운) 검증. chain-3 종료 후 실행.
set -uo pipefail
cd "$(dirname "$0")/.."
export WARM=10 STEADY=30 FAULT=40 RECOVER=40 RPS=300
REPS=${REPS:-5}
run_variant() { local set=$1 v=$2 sc=$3; for rep in $(seq 1 "$REPS"); do NAME="$set/$v/$sc/cluster/rps$RPS/rep$rep" bench/run.sh "$set" "$sc" "$RPS" "$rep" || { A=$(bench/api.sh GET /api/experiments/active | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))"); [ -n "$A" ] && { bench/api.sh POST "/api/experiments/$A/recover" >/dev/null; sleep 20; bench/api.sh POST "/api/experiments/$A/finish" >/dev/null; }; }; sleep 5; done; }
echo "[$(date +%T)] === chain-4: reruns of host-sleep-affected runs ==="
# Sentinel 스택(chain-3 종료 시점)에서 TILT 로 오염된 KILL_PRIMARY rep3 재실행
bench/wait-stable.sh 120 && bench/run.sh faults KILL_PRIMARY 300 3 || true
# commons-pool2 누락으로 실패했던 Sentinel pool-8 변형 재측정
REDIS_POOL_ENABLED=true LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
for rep in 1 2 3 4 5; do NAME="options/pool-8/KILL_PRIMARY/sentinel/rps300/rep$rep" bench/run.sh options KILL_PRIMARY 300 $rep || true; sleep 5; done
LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
# 19:05~19:36 절전(덮개 닫힘)으로 오염된 Sentinel 설정 실행 재측정
LAB_WAIT_REPLICAS=1 LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120; DELAY_MS=500 NAME="settings/wait-1/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep1" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 1 || true; LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
LAB_WAIT_REPLICAS=1 LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120; DELAY_MS=500 NAME="settings/wait-1/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep2" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 2 || true; LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
LAB_WAIT_REPLICAS=1 LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120; DELAY_MS=500 NAME="settings/wait-1/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep3" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 3 || true; LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
LAB_WAIT_REPLICAS=1 LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120; DELAY_MS=500 NAME="settings/wait-1/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep4" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 4 || true; LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
LAB_WAIT_REPLICAS=1 LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120; DELAY_MS=500 NAME="settings/wait-1/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep5" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 5 || true; LAB_TOPOLOGY=sentinel docker compose --profile sentinel up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' DELAY_MS=500 NAME="settings/aof-everysec/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep1" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 1 || true; sleep 5
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' DELAY_MS=500 NAME="settings/aof-everysec/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep2" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 2 || true; sleep 5
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' DELAY_MS=500 NAME="settings/aof-everysec/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep3" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 3 || true; sleep 5
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' DELAY_MS=500 NAME="settings/aof-everysec/NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY/sentinel/rps300/rep4" bench/run.sh settings NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY 300 4 || true; sleep 5
REDIS_SETTINGS='{"appendonly":"no","min-replicas-to-write":"0"}' NAME="settings/reset-all/KILL_PRIMARY/sentinel/rps300/rep1" bench/run.sh settings KILL_PRIMARY 300 1 || true
echo "[$(date +%T)] === chain-4: cluster adaptive refresh variants ==="
bench/switch.sh cluster
export LAB_TOPOLOGY=cluster
# 절전으로 오염된 node-timeout-15000 rep 3~5 재실행
CLUSTER_NODE_TIMEOUT=15000 bench/switch.sh cluster
for rep in 3 4 5; do NAME="timing/node-timeout-15000/KILL_PRIMARY/cluster/rps300/rep$rep" bench/run.sh timing KILL_PRIMARY 300 $rep || true; sleep 5; done
bench/switch.sh cluster
export LAB_TOPOLOGY=cluster
LETTUCE_ADAPTIVE_REFRESH=true LETTUCE_PERIODIC_REFRESH=false LETTUCE_ADAPTIVE_TIMEOUT=5s docker compose --profile cluster up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
run_variant options adaptive-only+trigger-timeout-5s KILL_PRIMARY
LETTUCE_ADAPTIVE_REFRESH=true LETTUCE_PERIODIC_REFRESH=false LETTUCE_ADAPTIVE_TIMEOUT=5s LETTUCE_RECONNECT_TRIGGER=2 docker compose --profile cluster up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
run_variant options adaptive-only+tt-5s+reconnect-2 KILL_PRIMARY
LETTUCE_ADAPTIVE_REFRESH=true LETTUCE_PERIODIC_REFRESH=true LETTUCE_REFRESH_PERIOD=5s LETTUCE_DISCONNECTED=REJECT_COMMANDS docker compose --profile cluster up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
run_variant options adaptive+periodic-5s+reject KILL_PRIMARY
# WAIT 구현 수정 후 Cluster wait-1 재측정 (복제 지연 500 ms + kill)
LAB_WAIT_REPLICAS=1 docker compose --profile cluster up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
export DELAY_MS=500; run_variant settings wait-1 NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY; unset DELAY_MS
docker compose --profile cluster up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
echo "[$(date +%T)] === chain-4 done ==="

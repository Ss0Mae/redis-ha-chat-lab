#!/usr/bin/env bash
# Lettuce 옵션 / 데이터 안전성 설정 / 감지 시간 설정 비교. 각 변형은 앱(또는 Redis)을 그 설정으로 다시 띄운 뒤 같은 장애를 반복한다.
set -uo pipefail
cd "$(dirname "$0")/.."
export WARM=10 STEADY=30 FAULT=40 RECOVER=40 RPS=${RPS:-300}
REPS=${REPS:-5}
restart_app() { # env 변수들을 그대로 넘겨 앱만 재생성
  docker compose --profile "$1" up -d --force-recreate app >/dev/null 2>&1; bench/wait-stable.sh 120
}
run_variant() { # $1 set, $2 variant, $3 scenario, extra env 는 호출 전 export
  local set=$1 v=$2 sc=$3
  for rep in $(seq 1 "$REPS"); do
    NAME="$set/$v/$sc/$(bench/api.sh GET /api/redis/topology | python3 -c "import sys,json;print(json.load(sys.stdin)['topology'])")/rps$RPS/rep$rep" bench/run.sh "$set" "$sc" "$RPS" "$rep" || { A=$(bench/api.sh GET /api/experiments/active | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))"); [ -n "$A" ] && { bench/api.sh POST "/api/experiments/$A/recover" >/dev/null; sleep 20; bench/api.sh POST "/api/experiments/$A/finish" >/dev/null; }; }
    sleep 5
  done
}

# ---------- Cluster: Lettuce 토폴로지 갱신 옵션 ----------
echo "[$(date +%T)] === cluster options ==="
bench/switch.sh cluster
export LAB_TOPOLOGY=cluster
LETTUCE_ADAPTIVE_REFRESH=false LETTUCE_PERIODIC_REFRESH=false restart_app cluster; run_variant options refresh-none KILL_PRIMARY
LETTUCE_ADAPTIVE_REFRESH=false LETTUCE_PERIODIC_REFRESH=true LETTUCE_REFRESH_PERIOD=60s restart_app cluster; run_variant options periodic-60s KILL_PRIMARY
LETTUCE_ADAPTIVE_REFRESH=true LETTUCE_PERIODIC_REFRESH=false restart_app cluster; run_variant options adaptive-only KILL_PRIMARY
LETTUCE_ADAPTIVE_REFRESH=true LETTUCE_PERIODIC_REFRESH=true LETTUCE_REFRESH_PERIOD=5s restart_app cluster; run_variant options adaptive+periodic-5s KILL_PRIMARY
LETTUCE_DISCONNECTED=REJECT_COMMANDS restart_app cluster; run_variant options reject-commands KILL_PRIMARY
LETTUCE_REPLAY_NON_IDEMPOTENT=false restart_app cluster; run_variant options no-replay-nonidempotent KILL_PRIMARY
LAB_RETRY=all restart_app cluster; run_variant options retry-all KILL_PRIMARY
REDIS_COMMAND_TIMEOUT=200ms restart_app cluster; run_variant options timeout-200ms KILL_PRIMARY
LETTUCE_TCP_USER_TIMEOUT=3s restart_app cluster; run_variant options tcp-user-timeout-3s PAUSE_PRIMARY; run_variant options tcp-user-timeout-3s PARTITION_PRIMARY_FROM_APP
restart_app cluster
# ---------- Cluster: 데이터 안전성 설정 ----------
echo "[$(date +%T)] === cluster settings ==="
export DELAY_MS=500
run_variant settings baseline NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"min-replicas-to-write":"1","min-replicas-max-lag":"1"}' run_variant settings min-replicas NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
LAB_WAIT_REPLICAS=1 restart_app cluster; run_variant settings wait-1 NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
restart_app cluster
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' run_variant settings aof-everysec NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"always"}' run_variant settings aof-always NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"appendonly":"no"}' run_variant settings reset-aof KILL_PRIMARY   # 설정 원복 확인용 1회
unset DELAY_MS
# ---------- Cluster: node-timeout 민감도, require-full-coverage ----------
echo "[$(date +%T)] === cluster timing/coverage ==="
CLUSTER_NODE_TIMEOUT=1000 bench/switch.sh cluster; run_variant timing node-timeout-1000 KILL_PRIMARY
CLUSTER_NODE_TIMEOUT=15000 bench/switch.sh cluster; run_variant timing node-timeout-15000 KILL_PRIMARY
CLUSTER_FULL_COVERAGE=no bench/switch.sh cluster; run_variant coverage full-coverage-no STOP_PRIMARY_AND_ITS_REPLICA
# ---------- Sentinel ----------
echo "[$(date +%T)] === sentinel options/settings ==="
bench/switch.sh sentinel
export LAB_TOPOLOGY=sentinel
LETTUCE_DISCONNECTED=REJECT_COMMANDS restart_app sentinel; run_variant options reject-commands KILL_PRIMARY
LETTUCE_REPLAY_NON_IDEMPOTENT=false restart_app sentinel; run_variant options no-replay-nonidempotent KILL_PRIMARY
LAB_RETRY=all restart_app sentinel; run_variant options retry-all KILL_PRIMARY
REDIS_COMMAND_TIMEOUT=200ms restart_app sentinel; run_variant options timeout-200ms KILL_PRIMARY
REDIS_POOL_ENABLED=true restart_app sentinel; run_variant options pool-8 KILL_PRIMARY
# hang 형 장애(pause·파티션)에서 클라이언트가 옛 Primary 연결을 붙잡는 문제의 해법 후보 두 가지
LETTUCE_TCP_USER_TIMEOUT=3s restart_app sentinel; run_variant options tcp-user-timeout-3s KILL_PRIMARY; run_variant options tcp-user-timeout-3s PAUSE_PRIMARY; run_variant options tcp-user-timeout-3s PARTITION_PRIMARY_FROM_APP
LETTUCE_READ_FROM=UPSTREAM restart_app sentinel; run_variant options read-from-master KILL_PRIMARY; run_variant options read-from-master PAUSE_PRIMARY; run_variant options read-from-master PARTITION_PRIMARY_FROM_SENTINELS
LETTUCE_READ_FROM=UPSTREAM LETTUCE_TCP_USER_TIMEOUT=3s restart_app sentinel; run_variant options read-from-master+tut-3s PAUSE_PRIMARY
restart_app sentinel
# split-brain 창(pause 후 복귀, Sentinel 과의 단절)에서 min-replicas-to-write 가 유실을 막는가
REDIS_SETTINGS='{"min-replicas-to-write":"1","min-replicas-max-lag":"1"}' run_variant settings min-replicas PAUSE_PRIMARY
REDIS_SETTINGS='{"min-replicas-to-write":"1","min-replicas-max-lag":"1"}' run_variant settings min-replicas PARTITION_PRIMARY_FROM_SENTINELS
REDIS_SETTINGS='{"min-replicas-to-write":"0"}' run_variant settings reset-minrep KILL_PRIMARY
export DELAY_MS=500
run_variant settings baseline NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"min-replicas-to-write":"1","min-replicas-max-lag":"1"}' run_variant settings min-replicas NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
LAB_WAIT_REPLICAS=1 restart_app sentinel; run_variant settings wait-1 NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
restart_app sentinel
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"everysec"}' run_variant settings aof-everysec NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"appendonly":"yes","appendfsync":"always"}' run_variant settings aof-always NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY
REDIS_SETTINGS='{"appendonly":"no"}' run_variant settings reset-aof KILL_PRIMARY
unset DELAY_MS
# 재기동한 옛 Primary 가 빈 채로 master 로 남는 문제: master-reboot-down-after-period 10 s
SENTINEL_REBOOT_DOWN=10000 bench/switch.sh sentinel; run_variant timing reboot-down-10s STOP_SENTINELS_THEN_KILL_PRIMARY; run_variant timing reboot-down-10s KILL_PRIMARY
SENTINEL_DOWN_AFTER=1000 bench/switch.sh sentinel; run_variant timing down-after-1000 KILL_PRIMARY
SENTINEL_DOWN_AFTER=15000 bench/switch.sh sentinel; run_variant timing down-after-15000 KILL_PRIMARY
bench/switch.sh sentinel
echo "[$(date +%T)] === all-options done ==="

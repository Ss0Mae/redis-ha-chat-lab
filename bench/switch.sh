#!/usr/bin/env bash
# 토폴로지 전환: Redis 관련 컨테이너만 지우고 원하는 프로필로 다시 띄운 뒤 앱을 새 LAB_TOPOLOGY 로 재생성한다.
#   bench/switch.sh sentinel|cluster|single  (추가 env: SENTINEL_DOWN_AFTER, CLUSTER_NODE_TIMEOUT, CLUSTER_FULL_COVERAGE, LETTUCE_*, LAB_* 등은 그대로 전달)
set -uo pipefail
cd "$(dirname "$0")/.."
T=$1
PROFILE=$T; [ "$T" = "sentinel-toxi" ] && PROFILE=sentinel
ALL="r-0 fault-r-0 r-a-1 r-a-2 r-a-3 s-1 s-2 s-3 fault-r-a-1 fault-r-a-2 fault-r-a-3 c-1 c-2 c-3 c-4 c-5 c-6 cluster-init fault-c-1 fault-c-2 fault-c-3 fault-c-4 fault-c-5 fault-c-6"
docker compose --profile single --profile sentinel --profile cluster rm -sf $ALL >/dev/null 2>&1
LAB_TOPOLOGY=$T docker compose --profile "$PROFILE" up -d --force-recreate app >/dev/null 2>&1
LAB_TOPOLOGY=$T docker compose --profile "$PROFILE" up -d >/dev/null 2>&1
bench/wait-stable.sh 150

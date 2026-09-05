#!/usr/bin/env bash
# 앱이 뜨고 토폴로지가 안정(모든 노드 UP, stable=true)될 때까지 최대 $1 초 대기.
LIMIT=${1:-120}
for i in $(seq 1 "$LIMIT"); do
  if curl -sf localhost:8085/actuator/health >/dev/null 2>&1; then
    R=$(curl -s localhost:8085/api/redis/topology | python3 -c "
import sys,json
try:
  t=json.load(sys.stdin); down=[n['name'] for n in t['nodes'] if n['status']!='UP']
  print('ok' if t['stable'] and not down and t['nodes'] else 'wait '+str(down))
except Exception as e: print('wait')")
    if [ "$R" = "ok" ]; then
      # 앱의 공유 연결이 강등된 노드에 붙어 있으면(READONLY) 연결을 리셋해 Sentinel/Cluster 를 다시 조회하게 한다 — 실험 간 오염 방지
      for k in 1 2 3; do
        O=$(curl -s -X PUT localhost:8085/api/chat/users/1/presence | python3 -c "import sys,json;print(json.load(sys.stdin).get('outcome',''))" 2>/dev/null)
        [ "$O" = "OK" ] && exit 0
        echo "probe write $O; resetting app connection ($k)"; curl -s -X POST -H "X-Lab-Admin-Token: ${LAB_ADMIN_TOKEN:-lab-admin}" localhost:8085/api/redis/reset-connection >/dev/null; sleep 3
      done
      exit 1
    fi
  fi
  sleep 1
done
echo "not stable after ${LIMIT}s: $R"; exit 1

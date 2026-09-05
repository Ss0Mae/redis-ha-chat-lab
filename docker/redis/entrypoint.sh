#!/bin/sh
# 첫 기동에만 템플릿을 /data 로 복사한다. 이후 재시작은 Sentinel/Cluster 가 CONFIG REWRITE 한 파일을 그대로 써서
# 승격·강등된 역할이 유지된다(읽기 전용 설정 + 명령줄 --replicaof 는 재시작 때 부트스트랩 역할로 되돌아가는 함정).
set -e
if [ ! -f /data/redis.conf ]; then
  cp /conf/redis.conf /data/redis.conf
  [ -n "${REPLICAOF:-}" ] && echo "replicaof $REPLICAOF" >> /data/redis.conf
fi
exec redis-server /data/redis.conf "$@"

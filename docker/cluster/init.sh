#!/bin/sh
# 6 노드가 모두 뜨면 3 Primary + 3 Replica 로 클러스터를 만든다. 이미 만들어져 있으면 건너뛴다.
NODES="172.28.2.11:6379 172.28.2.12:6379 172.28.2.13:6379 172.28.2.14:6379 172.28.2.15:6379 172.28.2.16:6379"
for n in $NODES; do
  until redis-cli -h ${n%:*} -p ${n#*:} ping 2>/dev/null | grep -q PONG; do sleep 0.5; done
done
if redis-cli -h 172.28.2.11 CLUSTER INFO | grep -q 'cluster_known_nodes:6'; then echo "cluster already created"; exit 0; fi
redis-cli --cluster create $NODES --cluster-replicas 1 --cluster-yes
until redis-cli -h 172.28.2.11 CLUSTER INFO | grep -q 'cluster_state:ok'; do sleep 0.5; done
redis-cli -h 172.28.2.11 CLUSTER NODES

#!/bin/sh
# 사용: netem.sh <delay_ms> <loss_pct> [dst_ip ...]
# dst 가 있으면 그 목적지로 나가는 패킷에만, 없으면 모든 송신 패킷에 지연·손실을 준다.
delay=$1; loss=$2; shift 2
tc qdisc del dev eth0 root 2>/dev/null
if [ $# -eq 0 ]; then
  tc qdisc add dev eth0 root netem delay ${delay}ms loss ${loss}%
else
  tc qdisc add dev eth0 root handle 1: prio bands 4
  tc qdisc add dev eth0 parent 1:4 handle 40: netem delay ${delay}ms loss ${loss}%
  for ip in "$@"; do tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst "$ip"/32 flowid 1:4; done
fi
tc qdisc show dev eth0

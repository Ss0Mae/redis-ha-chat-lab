#!/bin/sh
# 사용: drop.sh add|del <ip> [<ip>...]  — 지정한 상대와의 양방향 패킷을 버린다(파티션).
op=$1; shift
for ip in "$@"; do
  case $op in
    add) iptables -I INPUT -s "$ip" -j DROP; iptables -I OUTPUT -d "$ip" -j DROP ;;
    del) iptables -D INPUT -s "$ip" -j DROP 2>/dev/null; iptables -D OUTPUT -d "$ip" -j DROP 2>/dev/null ;;
  esac
done
iptables -S | grep -c DROP || true

#!/bin/sh
iptables -F INPUT; iptables -F OUTPUT; tc qdisc del dev eth0 root 2>/dev/null; echo cleared

package com.seongmin.redislab.topology;

import java.time.Instant;
import java.util.Map;

/** 대시보드 노드 카드 1장. role: PRIMARY|REPLICA|SENTINEL, status: UP|DOWN|SUSPECTED|FAILOVER|SYNCING */
public record NodeState(String name, String ip, int port, String role, String status, String masterOf, String slots,
		long replOffset, long lagBytes, String linkStatus, Instant lastChange, Map<String, Object> extra) {
	public NodeState withLastChange(Instant t) { return new NodeState(name, ip, port, role, status, masterOf, slots, replOffset, lagBytes, linkStatus, t, extra); }
	public String signature() { return role + "|" + status + "|" + masterOf + "|" + slots; }
}

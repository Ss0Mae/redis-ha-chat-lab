package com.seongmin.redislab.topology;

import java.util.List;

public interface TopologyWatcher extends ShardResolver {
	TopologySnapshot snapshot();
	/** 현재 Primary 이름(Cluster 는 shard 순서대로). */
	List<String> primaries();
	/** 해당 Primary 를 복제하는 Replica 이름들. */
	List<String> replicasOf(String primary);
	/** 실험 준비용: 모든 도달 가능한 Redis 노드에 CONFIG SET. */
	void configSetAll(String param, String value);
}

package com.seongmin.redislab.topology;

/** 키가 속한 Primary 의 순번. Sentinel/단일은 항상 0, Cluster 는 slot 을 담당하는 Primary 의 순번(0..2). */
public interface ShardResolver {
	int shardOf(String key);
}

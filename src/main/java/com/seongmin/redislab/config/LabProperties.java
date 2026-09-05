package com.seongmin.redislab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 실험실 전용 설정. spring.data.redis.* 는 Boot 가 처리하고, 여기는 Lettuce 비교 옵션과 장애 주입 경로다. */
@ConfigurationProperties("lab")
public record LabProperties(String topology, String adminToken, String dockerApi, String toxiproxyApi,
		int maxFaultSeconds, String retry, Integer waitReplicas, Integer waitTimeoutMs, Lettuce lettuce) {

	public record Lettuce(String disconnectedBehavior, boolean replayNonIdempotent, Duration adaptiveTimeout, Integer reconnectAttemptsTrigger, Boolean periodicRefresh,
			Duration tcpUserTimeout, Boolean keepAlive, String readFrom) {}

	public boolean isCluster() { return "cluster".equals(topology); }
	public boolean isSentinel() { return topology != null && topology.startsWith("sentinel"); }
}

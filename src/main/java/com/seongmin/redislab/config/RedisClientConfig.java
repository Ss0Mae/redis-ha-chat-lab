package com.seongmin.redislab.config;

import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.metrics.LabMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.event.ClusterTopologyChangedEvent;
import io.lettuce.core.event.connection.ConnectedEvent;
import io.lettuce.core.event.connection.DisconnectedEvent;
import io.lettuce.core.event.connection.ReconnectAttemptEvent;
import io.lettuce.core.event.connection.ReconnectFailedEvent;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Lettuce 비교 옵션을 한 곳에서. Boot 의 spring.data.redis.* 위에 lab.lettuce.* 를 얹는다.
 * EventBus 구독으로 재연결·토폴로지 갱신 시각(T4)을 기록한다.
 */
@Configuration
public class RedisClientConfig {

	/** 재연결 시 재전송하면 중복 실행되는 명령. replay-non-idempotent=false 일 때만 걸러낸다. */
	static final Set<String> NON_IDEMPOTENT = Set.of("INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY", "XADD", "RPUSH", "LPUSH", "APPEND");

	@Bean(destroyMethod = "shutdown")
	public ClientResources clientResources(LabEventBus bus, LabMetrics metrics) {
		DefaultClientResources res = DefaultClientResources.create();
		res.eventBus().get().subscribe(e -> {
			if (e instanceof ConnectedEvent ce) {
				metrics.count("redis_connection_events_total", "type", "connected");
				bus.publish(LabEvent.of("event", "lettuce", "ConnectedEvent", Map.of("remote", String.valueOf(ce.remoteAddress()))));
			} else if (e instanceof DisconnectedEvent de) {
				metrics.count("redis_connection_events_total", "type", "disconnected");
				bus.publish(LabEvent.of("event", "lettuce", "DisconnectedEvent", Map.of("remote", String.valueOf(de.remoteAddress()))));
			} else if (e instanceof ReconnectAttemptEvent ra) {
				metrics.count("redis_reconnect_total");
				if (ra.getAttempt() <= 3 || ra.getAttempt() % 10 == 0)
					bus.publish(LabEvent.of("event", "lettuce", "ReconnectAttemptEvent", Map.of("remote", String.valueOf(ra.remoteAddress()), "attempt", ra.getAttempt())));
			} else if (e instanceof ReconnectFailedEvent rf) {
				metrics.count("redis_connection_failures_total");
				if (rf.getAttempt() <= 3 || rf.getAttempt() % 10 == 0)
					bus.publish(LabEvent.of("event", "lettuce", "ReconnectFailedEvent", Map.of("remote", String.valueOf(rf.remoteAddress()), "attempt", rf.getAttempt(), "cause", String.valueOf(rf.getCause()))));
			} else if (e instanceof ClusterTopologyChangedEvent ct) {
				metrics.count("redis_topology_refresh_total");
				String after = ct.after().stream().map(n -> n.getUri().getHost() + ":" + n.getUri().getPort() + (n.getRole().isUpstream() ? "(M " + n.getSlots().size() + ")" : "(R)")).sorted().toList().toString();
				bus.publish(LabEvent.of("event", "lettuce", "ClusterTopologyChangedEvent", Map.of("after", after)));
			}
		});
		return res;
	}

	@Bean
	public LettuceClientConfigurationBuilderCustomizer lettuceOptions(LabProperties lab, RedisProperties redis) {
		return builder -> {
			Duration timeout = redis.getTimeout() != null ? redis.getTimeout() : Duration.ofSeconds(1);
			Duration connect = redis.getConnectTimeout() != null ? redis.getConnectTimeout() : Duration.ofSeconds(1);
			ClientOptions.Builder b;
			if (lab.isCluster()) {
				var refresh = redis.getLettuce().getCluster().getRefresh();
				ClusterTopologyRefreshOptions.Builder r = ClusterTopologyRefreshOptions.builder().dynamicRefreshSources(refresh.isDynamicRefreshSources());
				if (refresh.getPeriod() != null && !Boolean.FALSE.equals(lab.lettuce().periodicRefresh())) r.enablePeriodicRefresh(refresh.getPeriod());
				if (refresh.isAdaptive()) r.enableAllAdaptiveRefreshTriggers();
				if (lab.lettuce().adaptiveTimeout() != null) r.adaptiveRefreshTriggersTimeout(lab.lettuce().adaptiveTimeout());
				if (lab.lettuce().reconnectAttemptsTrigger() != null) r.refreshTriggersReconnectAttempts(lab.lettuce().reconnectAttemptsTrigger());
				Integer maxRedirects = redis.getCluster() != null ? redis.getCluster().getMaxRedirects() : null;
				b = ClusterClientOptions.builder().topologyRefreshOptions(r.build()).maxRedirects(maxRedirects == null ? 3 : maxRedirects).validateClusterNodeMembership(false);
			} else {
				b = ClientOptions.builder();
			}
			SocketOptions.Builder so = SocketOptions.builder().connectTimeout(connect).keepAlive(Boolean.TRUE.equals(lab.lettuce().keepAlive()));
			Duration tut = lab.lettuce().tcpUserTimeout();
			if (tut != null && !tut.isZero()) so.tcpUserTimeout(SocketOptions.TcpUserTimeoutOptions.builder().enable().tcpUserTimeout(tut).build());
			b.autoReconnect(true)
					.disconnectedBehavior(ClientOptions.DisconnectedBehavior.valueOf(lab.lettuce().disconnectedBehavior()))
					.timeoutOptions(TimeoutOptions.enabled(timeout))
					.socketOptions(so.build());
			// readFrom 을 주면 Spring 은 MasterReplica 연결(Sentinel 이벤트 구독, 승격 즉시 전환)을 쓴다.
			if (lab.lettuce().readFrom() != null && !lab.lettuce().readFrom().isBlank()) builder.readFrom(io.lettuce.core.ReadFrom.valueOf(lab.lettuce().readFrom()));
			// Lettuce 6.6: 필터가 true 를 돌려주는 명령은 재연결 후 재전송하지 않는다(기본은 전부 재전송).
			if (!lab.lettuce().replayNonIdempotent()) b.replayFilter(cmd -> NON_IDEMPOTENT.contains(String.valueOf(cmd.getType())));
			builder.clientOptions(b.build());
		};
	}
}

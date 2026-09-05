package com.seongmin.redislab.web;

import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.record.RecordingService;
import com.seongmin.redislab.topology.TopologySnapshot;
import com.seongmin.redislab.topology.TopologyWatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/redis")
public class RedisController {

	private final TopologyWatcher topology;
	private final LabEventBus bus;
	private final RecordingService recording;
	private final MeterRegistry registry;
	private final com.seongmin.redislab.config.LabProperties lab;
	private final org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory;

	public RedisController(TopologyWatcher topology, LabEventBus bus, RecordingService recording, MeterRegistry registry, com.seongmin.redislab.config.LabProperties lab, org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
		this.topology = topology; this.bus = bus; this.recording = recording; this.registry = registry; this.lab = lab; this.connectionFactory = connectionFactory;
	}

	@GetMapping("/topology") public TopologySnapshot topology() { return topology.snapshot(); }
	@GetMapping("/nodes") public Object nodes() { return topology.snapshot().nodes(); }

	@GetMapping("/metrics")
	public Map<String, Object> metrics() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("lastSecond", recording.lastSample());
		m.put("phase", recording.phase());
		m.put("recordingMode", recording.mode());
		Map<String, Double> counters = new TreeMap<>();
		for (String name : new String[]{"redis_commands_total", "redis_command_failures_total", "redis_reconnect_total", "redis_connection_failures_total", "redis_topology_refresh_total", "redis_retry_total", "redis_cluster_redirect_total", "redis_failover_total"}) {
			for (Counter c : registry.find(name).counters()) counters.merge(name + c.getId().getTags().stream().map(t -> " " + t.getKey() + "=" + t.getValue()).reduce("", String::concat), c.count(), Double::sum);
		}
		m.put("counters", counters);
		m.put("client", Map.of("topology", String.valueOf(lab.topology()), "retry", String.valueOf(lab.retry()), "waitReplicas", String.valueOf(lab.waitReplicas()), "lettuce", String.valueOf(lab.lettuce()),
				"nativeTransport", epollAvailable()));
		return m;
	}

	private static boolean epollAvailable() {
		try { return (Boolean) Class.forName("io.netty.channel.epoll.Epoll").getMethod("isAvailable").invoke(null); } catch (Throwable t) { return false; }
	}

	@GetMapping("/events/stream")
	public SseEmitter stream() {
		SseEmitter em = bus.register();
		try {
			em.send(SseEmitter.event().name("hello").data(Map.of("recent", bus.recent(), "topology", topology.snapshot())));
		} catch (Exception ignored) {}
		return em;
	}

	@GetMapping("/events/recent") public Object recent() { return bus.recent(); }

	/** 실험 도구용: 공유 Lettuce 연결을 끊어 다음 명령에서 Sentinel/Cluster 를 다시 조회하게 한다(READONLY 로 굳은 연결 복구). */
	@org.springframework.web.bind.annotation.PostMapping("/reset-connection")
	public Map<String, Object> resetConnection(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Lab-Admin-Token", required = false) String token) {
		if (!lab.adminToken().equals(token)) throw new IllegalArgumentException("X-Lab-Admin-Token required");
		if (connectionFactory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lcf) { lcf.resetConnection(); return Map.of("reset", true); }
		return Map.of("reset", false, "factory", connectionFactory.getClass().getSimpleName());
	}

	@Scheduled(fixedRate = 15000) public void heartbeat() { bus.heartbeat(); }
}

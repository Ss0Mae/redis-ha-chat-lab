package com.seongmin.redislab.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 커스텀 메트릭. Counter = 누적 건수(rate 로 초당), Timer = 분포(p95/p99), Gauge = 순간값.
 */
@Component
public class LabMetrics {

	private final MeterRegistry reg;
	private final Map<String, AtomicLong> lagBytes = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> nodeUp = new ConcurrentHashMap<>();

	public LabMetrics(MeterRegistry reg) {
		this.reg = reg;
	}

	public void command(String op, String outcome, long nanos) {
		Counter.builder("redis_commands_total").tag("op", op).tag("outcome", outcome).register(reg).increment();
		Timer.builder("redis_command_duration_seconds").tag("op", op).publishPercentileHistogram().register(reg).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
		if (!"OK".equals(outcome)) Counter.builder("redis_command_failures_total").tag("op", op).tag("error", outcome).register(reg).increment();
	}

	public void workload(String op, String outcome, long nanos) {
		Counter.builder("workload_requests_total").tag("op", op).tag("outcome", outcome).register(reg).increment();
		Timer.builder("workload_request_duration_seconds").tag("op", op).publishPercentileHistogram().register(reg).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
	}

	public void count(String name, String... tags) {
		Counter.builder(name).tags(tags).register(reg).increment();
	}

	private final Map<String, AtomicLong> failoverLast = new ConcurrentHashMap<>();

	public void failoverPhase(String topology, String phase, long millis) {
		Timer.builder("redis_failover_duration_seconds").tag("topology", topology).tag("phase", phase).register(reg)
				.record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
		// Timer 의 max 는 2분 창 뒤 사라지므로 "최근 실험 값" 은 Gauge 로 따로 둔다(대시보드 타임라인 패널용)
		failoverLast.computeIfAbsent(topology + "|" + phase, k -> {
			AtomicLong v = new AtomicLong();
			Gauge.builder("redis_failover_last_seconds", v, x -> x.get() / 1000.0).tag("topology", topology).tag("phase", phase).register(reg);
			return v;
		}).set(millis);
	}

	public void lag(String node, long bytes) {
		lagBytes.computeIfAbsent(node, n -> {
			AtomicLong v = new AtomicLong();
			Gauge.builder("redis_replication_lag_bytes", v, AtomicLong::get).tag("node", n).register(reg);
			return v;
		}).set(bytes);
	}

	public void nodeUp(String node, String role, boolean up) {
		nodeUp.computeIfAbsent(node + "|" + role, k -> {
			AtomicInteger v = new AtomicInteger();
			Gauge.builder("redis_node_up", v, AtomicInteger::get).tag("node", node).tag("role", role).register(reg);
			return v;
		}).set(up ? 1 : 0);
	}
}

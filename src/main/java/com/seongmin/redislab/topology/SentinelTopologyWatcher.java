package com.seongmin.redislab.topology;

import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.metrics.LabMetrics;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Sentinel 구성 감시. (1) Sentinel 3대의 pubsub(+sdown/+odown/+switch-master …)을 구독해 시각을 찍고
 * (2) 200 ms 마다 노드 INFO 로 역할·offset·링크 상태를 읽어 스냅샷을 만든다. 단일 Redis 프로필도 같은 코드로 감시한다.
 */
@Component
@Profile({"single", "sentinel", "sentinel-toxi"})
public class SentinelTopologyWatcher implements TopologyWatcher {

	private final FaultCatalog catalog;
	private final LabEventBus bus;
	private final LabMetrics metrics;
	private final NodeProbe probe = new NodeProbe();
	private final List<StatefulRedisPubSubConnection<String, String>> pubsubs = new ArrayList<>();
	private final Map<String, String> lastSig = new HashMap<>();
	private final Map<String, Instant> lastChange = new HashMap<>();
	private volatile TopologySnapshot snapshot;
	private volatile boolean lastStable = true;
	private volatile String lastPrimary;

	public SentinelTopologyWatcher(FaultCatalog catalog, LabEventBus bus, LabMetrics metrics) {
		this.catalog = catalog;
		this.bus = bus;
		this.metrics = metrics;
		for (var n : catalog.nodes()) probe.register(n.name(), n.ip(), n.port());
		for (var s : catalog.sentinels()) subscribe(s);
		snapshot = new TopologySnapshot(Instant.now(), catalog.topology(), List.of(), List.of(), false, Map.of());
	}

	private void subscribe(FaultCatalog.Node s) {
		var conn = probe.client().connectPubSub(RedisURI.builder().withHost(s.ip()).withPort(s.port()).withTimeout(Duration.ofSeconds(1)).build());
		conn.addListener(new RedisPubSubAdapter<>() {
			@Override public void message(String pattern, String channel, String message) {
				bus.publish(LabEvent.of("event", "sentinel:" + s.name(), channel, message));
			}
		});
		conn.sync().psubscribe("*");
		pubsubs.add(conn);
	}

	@Scheduled(fixedDelay = 200)
	public void tick() {
		List<NodeState> nodes = new ArrayList<>();
		Map<String, Map<String, String>> infos = new HashMap<>();
		for (var n : catalog.redisNodes()) infos.put(n.name(), probe.info(n.name(), "replication"));
		// Sentinel 이 보는 master 상태(플래그)
		Map<String, String> master = null;
		int sentinelsUp = 0;
		for (var s : catalog.sentinels()) {
			Map<String, String> r = probe.sentinelMaster(s.name(), "chat-primary");
			boolean up = r != null;
			if (up) sentinelsUp++;
			if (up && master == null) master = r;
			nodes.add(state(s.name(), s.ip(), s.port(), "SENTINEL", up ? "UP" : "DOWN", null, null, 0, 0, null, Map.of()));
			metrics.nodeUp(s.name(), "SENTINEL", up);
		}
		String primaryName = null;
		long primaryOffset = 0;
		for (var n : catalog.redisNodes()) {
			var info = infos.get(n.name());
			if (info != null && "master".equals(info.get("role"))) {
				boolean sentinelSaysThis = master == null || master.get("ip") == null || master.get("ip").equals(n.ip());
				if (primaryName == null || sentinelSaysThis) { primaryName = n.name(); primaryOffset = parseLong(info.get("master_repl_offset")); }
			}
		}
		String masterFlags = master == null ? "" : String.valueOf(master.get("flags"));
		for (var n : catalog.redisNodes()) {
			var info = infos.get(n.name());
			if (info == null) { nodes.add(state(n.name(), n.ip(), n.port(), "?", "DOWN", null, null, 0, 0, null, Map.of())); metrics.nodeUp(n.name(), "REDIS", false); continue; }
			metrics.nodeUp(n.name(), "REDIS", true);
			boolean isMaster = "master".equals(info.get("role"));
			String role = isMaster ? "PRIMARY" : "REPLICA";
			String status = "UP", masterOf = null, link = null;
			long offset;
			if (isMaster) {
				offset = parseLong(info.get("master_repl_offset"));
				if (n.name().equals(primaryName) && (masterFlags.contains("s_down") || masterFlags.contains("o_down"))) status = "SUSPECTED";
				if (masterFlags.contains("failover_in_progress")) status = "FAILOVER";
			} else {
				offset = parseLong(info.get("slave_repl_offset"));
				link = info.get("master_link_status");
				masterOf = catalog.nameOf(info.getOrDefault("master_host", "?"));
				if ("1".equals(info.get("master_sync_in_progress"))) status = "SYNCING";
				else if (!"up".equals(link)) status = "SUSPECTED";
				metrics.lag(n.name(), Math.max(0, primaryOffset - offset));
			}
			nodes.add(state(n.name(), n.ip(), n.port(), role, status, masterOf, null, offset, isMaster ? 0 : Math.max(0, primaryOffset - offset), link,
					Map.of("connected_slaves", info.getOrDefault("connected_slaves", "0"), "master_link_down_since", info.getOrDefault("master_link_down_since_seconds", ""))));
		}
		final String p = primaryName;
		boolean stable = p != null && !masterFlags.contains("down") && !masterFlags.contains("failover")
				&& nodes.stream().filter(x -> "PRIMARY".equals(x.role())).count() == 1 // 재기동한 옛 Primary 가 아직 master 로 떠 있으면 불안정
				&& nodes.stream().filter(x -> "REPLICA".equals(x.role())).allMatch(x -> "UP".equals(x.status()) && p.equals(x.masterOf()))
				&& (catalog.sentinels().isEmpty() || (master != null && master.get("ip") != null && catalog.nameOf(master.get("ip")).equals(p)));
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("sentinelsUp", sentinelsUp);
		info.put("masterFlags", masterFlags);
		info.put("sentinelMasterAddr", master == null ? null : master.get("ip") + ":" + master.get("port"));
		info.put("numSlaves", master == null ? null : master.get("num-slaves"));
		info.put("quorum", master == null ? null : master.get("quorum"));
		snapshot = new TopologySnapshot(Instant.now(), catalog.topology(), nodes, p == null ? List.of() : List.of(p), stable, info);
		bus.publish(LabEvent.of("topology", "watcher", "snapshot", snapshot));
		if (!Objects.equals(p, lastPrimary)) { bus.publish(LabEvent.of("event", "watcher", "PRIMARY_CHANGED", Map.of("from", String.valueOf(lastPrimary), "to", String.valueOf(p)))); lastPrimary = p; }
		if (stable != lastStable) { bus.publish(LabEvent.of("event", "watcher", stable ? "STABLE" : "UNSTABLE", info)); lastStable = stable; }
	}

	private NodeState state(String name, String ip, int port, String role, String status, String masterOf, String slots, long offset, long lag, String link, Map<String, Object> extra) {
		String sig = role + "|" + status + "|" + masterOf;
		if (!sig.equals(lastSig.get(name))) {
			if (lastSig.containsKey(name)) bus.publish(LabEvent.of("event", "watcher", "NODE_STATUS", Map.of("node", name, "from", lastSig.get(name), "to", sig)));
			lastSig.put(name, sig);
			lastChange.put(name, Instant.now());
		}
		return new NodeState(name, ip, port, role, status, masterOf, slots, offset, lag, link, lastChange.get(name), extra);
	}

	private static long parseLong(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

	@Override public TopologySnapshot snapshot() { return snapshot; }
	@Override public List<String> primaries() { return snapshot.primaries(); }
	@Override public List<String> replicasOf(String primary) { return snapshot.nodes().stream().filter(n -> "REPLICA".equals(n.role()) && primary.equals(n.masterOf())).map(NodeState::name).toList(); }
	@Override public int shardOf(String key) { return 0; }
	@Override public void configSetAll(String param, String value) { for (var n : catalog.redisNodes()) probe.call(n.name(), c -> c.configSet(param, value)); }

	@PreDestroy public void close() { pubsubs.forEach(StatefulRedisPubSubConnection::closeAsync); probe.close(); }
}

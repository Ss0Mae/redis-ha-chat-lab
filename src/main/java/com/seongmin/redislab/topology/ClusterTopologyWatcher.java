package com.seongmin.redislab.topology;

import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.metrics.LabMetrics;
import io.lettuce.core.cluster.SlotHash;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Cluster 감시. 200 ms 마다 도달 가능한 모든 노드의 CLUSTER NODES/INFO 를 읽어
 * fail?(PFAIL)→fail(FAIL), epoch 증가(선거 시작), slave→master(승격), cluster_state 변화를 이벤트로 낸다.
 * 안정 = 도달 가능한 노드들의 뷰가 같고 cluster_state:ok 이며 Replica 링크가 모두 up.
 */
@Component
@Profile("cluster")
public class ClusterTopologyWatcher implements TopologyWatcher {

	record Line(String id, String ip, int port, Set<String> flags, String masterId, long epoch, String link, List<int[]> slots) {}

	private final FaultCatalog catalog;
	private final LabEventBus bus;
	private final LabMetrics metrics;
	private final NodeProbe probe = new NodeProbe();
	private final Map<String, String> lastSig = new HashMap<>();
	private final Map<String, Instant> lastChange = new HashMap<>();
	private final Map<String, Set<String>> lastFlags = new HashMap<>();
	private volatile TopologySnapshot snapshot;
	private volatile boolean lastStable = true;
	private volatile long lastEpoch = -1;
	private volatile String lastState = "";
	private volatile int[] slotToShard = new int[SlotHash.SLOT_COUNT];
	private volatile List<String> primaries = List.of();

	public ClusterTopologyWatcher(FaultCatalog catalog, LabEventBus bus, LabMetrics metrics) {
		this.catalog = catalog;
		this.bus = bus;
		this.metrics = metrics;
		for (var n : catalog.nodes()) probe.register(n.name(), n.ip(), n.port());
		snapshot = new TopologySnapshot(Instant.now(), "cluster", List.of(), List.of(), false, Map.of());
	}

	@Scheduled(fixedDelay = 200)
	public void tick() {
		Map<String, List<Line>> views = new LinkedHashMap<>();
		Map<String, Map<String, String>> clusterInfo = new HashMap<>();
		Map<String, Map<String, String>> repl = new HashMap<>();
		for (var n : catalog.redisNodes()) {
			String cn = probe.call(n.name(), c -> c.clusterNodes());
			if (cn == null) continue;
			views.put(n.name(), parse(cn));
			String ci = probe.call(n.name(), c -> c.clusterInfo());
			clusterInfo.put(n.name(), ci == null ? Map.of() : NodeProbe.parseInfo(ci));
			repl.put(n.name(), probe.info(n.name(), "replication"));
		}
		List<Line> view = views.isEmpty() ? List.of() : views.values().iterator().next();
		Map<String, Line> byIp = new HashMap<>();
		for (Line l : view) byIp.put(l.ip(), l);
		Map<String, Line> byId = new HashMap<>();
		for (Line l : view) byId.put(l.id(), l);

		List<NodeState> nodes = new ArrayList<>();
		Map<String, Long> masterOffsets = new HashMap<>();
		for (var n : catalog.redisNodes()) { var r = repl.get(n.name()); if (r != null && "master".equals(r.get("role"))) masterOffsets.put(n.name(), parseLong(r.get("master_repl_offset"))); }
		List<String> prim = new ArrayList<>();
		for (var n : catalog.redisNodes()) {
			Line l = byIp.get(n.ip());
			var r = repl.get(n.name());
			boolean reachable = r != null;
			metrics.nodeUp(n.name(), "REDIS", reachable);
			Set<String> flags = l == null ? Set.of() : l.flags();
			boolean isMaster = l != null ? flags.contains("master") : (r != null && "master".equals(r.get("role")));
			String role = isMaster ? "PRIMARY" : "REPLICA";
			String status = !reachable || flags.contains("fail") ? "DOWN" : flags.contains("fail?") ? "SUSPECTED" : "UP";
			String masterOf = null, link = null;
			long offset = 0, lag = 0;
			String slots = l == null ? null : slotsText(l.slots());
			if (isMaster) {
				offset = r == null ? 0 : parseLong(r.get("master_repl_offset"));
				if (reachable && !"DOWN".equals(status) && l != null && !l.slots().isEmpty()) prim.add(n.name());
			} else {
				if (l != null && byId.containsKey(l.masterId())) masterOf = catalog.nameOf(byId.get(l.masterId()).ip());
				if (r != null) {
					offset = parseLong(r.get("slave_repl_offset"));
					link = r.get("master_link_status");
					if ("1".equals(r.get("master_sync_in_progress"))) status = "SYNCING";
					else if (!"up".equals(link) && "UP".equals(status)) status = "SUSPECTED";
					if (masterOf != null && masterOffsets.containsKey(masterOf)) lag = Math.max(0, masterOffsets.get(masterOf) - offset);
					metrics.lag(n.name(), lag);
				}
			}
			var ci = clusterInfo.getOrDefault(n.name(), Map.of());
			nodes.add(state(n.name(), n.ip(), n.port(), role, status, masterOf, slots, offset, lag, link,
					Map.of("cluster_state", ci.getOrDefault("cluster_state", ""), "my_epoch", ci.getOrDefault("cluster_my_epoch", ""), "config_epoch", l == null ? "" : String.valueOf(l.epoch()))));
			// PFAIL/FAIL 전이 이벤트
			Set<String> prev = lastFlags.getOrDefault(n.name(), Set.of());
			if (l != null) {
				if (flags.contains("fail?") && !prev.contains("fail?") && !prev.contains("fail")) bus.publish(LabEvent.of("event", "watcher", "PFAIL", Map.of("node", n.name())));
				if (flags.contains("fail") && !prev.contains("fail")) bus.publish(LabEvent.of("event", "watcher", "FAIL", Map.of("node", n.name())));
				if (flags.contains("master") && prev.contains("slave")) bus.publish(LabEvent.of("event", "watcher", "PROMOTED", Map.of("node", n.name(), "slots", String.valueOf(slots))));
				if (flags.contains("slave") && prev.contains("master")) bus.publish(LabEvent.of("event", "watcher", "DEMOTED", Map.of("node", n.name(), "masterOf", String.valueOf(masterOf))));
				lastFlags.put(n.name(), flags);
			}
		}
		// shard 매핑: slot 시작이 작은 Primary 부터 0,1,2
		List<Line> masters = view.stream().filter(l -> l.flags().contains("master") && !l.slots().isEmpty()).sorted(Comparator.comparingInt(l -> l.slots().get(0)[0])).toList();
		int[] map = new int[SlotHash.SLOT_COUNT];
		Arrays.fill(map, -1);
		List<String> primNames = new ArrayList<>();
		for (int i = 0; i < masters.size(); i++) {
			primNames.add(catalog.nameOf(masters.get(i).ip()));
			for (int[] range : masters.get(i).slots()) for (int s = range[0]; s <= range[1]; s++) map[s] = i;
		}
		slotToShard = map;
		primaries = primNames;

		String firstState = views.isEmpty() ? "" : clusterInfo.getOrDefault(views.keySet().iterator().next(), Map.of()).getOrDefault("cluster_state", "");
		long epoch = views.isEmpty() ? lastEpoch : parseLong(clusterInfo.getOrDefault(views.keySet().iterator().next(), Map.of()).get("cluster_current_epoch"));
		boolean allOk = !views.isEmpty() && clusterInfo.values().stream().allMatch(ci -> "ok".equals(ci.get("cluster_state")));
		Set<String> sigs = new HashSet<>();
		for (List<Line> v : views.values()) sigs.add(v.stream().map(l -> l.ip() + ":" + (l.flags().contains("master") ? "M" : "S") + ":" + l.masterId() + ":" + slotsText(l.slots()) + ":" + (l.flags().contains("fail") ? "F" : l.flags().contains("fail?") ? "P" : "")).sorted().toList().toString());
		boolean replicasUp = nodes.stream().filter(x -> "REPLICA".equals(x.role()) && !"DOWN".equals(x.status())).allMatch(x -> "UP".equals(x.status()));
		boolean stable = allOk && sigs.size() == 1 && replicasUp && !primNames.isEmpty();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("cluster_state", firstState);
		info.put("cluster_current_epoch", epoch);
		info.put("reachable", views.size());
		info.put("views", sigs.size());
		info.put("slots_assigned", views.isEmpty() ? null : clusterInfo.getOrDefault(views.keySet().iterator().next(), Map.of()).get("cluster_slots_assigned"));
		snapshot = new TopologySnapshot(Instant.now(), "cluster", nodes, primNames, stable, info);
		bus.publish(LabEvent.of("topology", "watcher", "snapshot", snapshot));
		if (lastEpoch >= 0 && epoch > lastEpoch) bus.publish(LabEvent.of("event", "watcher", "EPOCH", Map.of("from", lastEpoch, "to", epoch)));
		lastEpoch = epoch;
		if (!firstState.equals(lastState)) { bus.publish(LabEvent.of("event", "watcher", "STATE", Map.of("from", lastState, "to", firstState))); lastState = firstState; }
		if (stable != lastStable) { bus.publish(LabEvent.of("event", "watcher", stable ? "STABLE" : "UNSTABLE", info)); lastStable = stable; }
	}

	static List<Line> parse(String clusterNodes) {
		List<Line> out = new ArrayList<>();
		for (String line : clusterNodes.split("\n")) {
			String[] f = line.trim().split(" ");
			if (f.length < 8) continue;
			String addr = f[1];
			int at = addr.indexOf('@'), comma = addr.indexOf(',');
			String hp = addr.substring(0, at < 0 ? (comma < 0 ? addr.length() : comma) : at);
			int colon = hp.lastIndexOf(':');
			List<int[]> slots = new ArrayList<>();
			for (int i = 8; i < f.length; i++) {
				if (f[i].startsWith("[")) continue; // 이동 중 slot 표기
				String[] r = f[i].split("-");
				slots.add(new int[]{Integer.parseInt(r[0]), Integer.parseInt(r[r.length - 1])});
			}
			slots.sort(Comparator.comparingInt(a -> a[0]));
			out.add(new Line(f[0], hp.substring(0, colon), Integer.parseInt(hp.substring(colon + 1)), new HashSet<>(Arrays.asList(f[2].split(","))), f[3], parseLong(f[6]), f[7], slots));
		}
		return out;
	}

	static String slotsText(List<int[]> slots) {
		if (slots == null || slots.isEmpty()) return "";
		StringBuilder b = new StringBuilder();
		for (int[] r : slots) { if (b.length() > 0) b.append(','); b.append(r[0]).append('-').append(r[1]); }
		return b.toString();
	}

	private NodeState state(String name, String ip, int port, String role, String status, String masterOf, String slots, long offset, long lag, String link, Map<String, Object> extra) {
		String sig = role + "|" + status + "|" + masterOf + "|" + slots;
		if (!sig.equals(lastSig.get(name))) {
			if (lastSig.containsKey(name)) bus.publish(LabEvent.of("event", "watcher", "NODE_STATUS", Map.of("node", name, "from", lastSig.get(name), "to", sig)));
			lastSig.put(name, sig);
			lastChange.put(name, Instant.now());
		}
		return new NodeState(name, ip, port, role, status, masterOf, slots, offset, lag, link, lastChange.get(name), extra);
	}

	private static long parseLong(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

	@Override public TopologySnapshot snapshot() { return snapshot; }
	@Override public List<String> primaries() { return primaries; }
	@Override public List<String> replicasOf(String primary) { return snapshot.nodes().stream().filter(n -> "REPLICA".equals(n.role()) && primary.equals(n.masterOf())).map(NodeState::name).toList(); }
	@Override public int shardOf(String key) { int s = slotToShard[SlotHash.getSlot(key)]; return s < 0 ? 0 : s; }
	@Override public void configSetAll(String param, String value) { for (var n : catalog.redisNodes()) probe.call(n.name(), c -> c.configSet(param, value)); }

	@PreDestroy public void close() { probe.close(); }
}

package com.seongmin.redislab.fault;

import com.seongmin.redislab.topology.FaultCatalog;
import com.seongmin.redislab.topology.TopologyWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시나리오 → 고정된 컨테이너·스크립트 호출. 논리 대상(primary, replica-1, sentinel-2, primary-2)을 현재 토폴로지에서 푼다.
 */
@Component
@Profile("local-experiment")
public class FaultExecutor {

	private static final Logger log = LoggerFactory.getLogger(FaultExecutor.class);
	private final DockerApi docker;
	private final ToxiproxyClient toxi;
	private final FaultCatalog catalog;
	private final TopologyWatcher topology;
	private final Set<String> dirtySidecars = ConcurrentHashMap.newKeySet();
	private final Set<String> stoppedNodes = ConcurrentHashMap.newKeySet();

	public FaultExecutor(DockerApi docker, ToxiproxyClient toxi, FaultCatalog catalog, TopologyWatcher topology) {
		this.docker = docker; this.toxi = toxi; this.catalog = catalog; this.topology = topology;
	}

	public DockerApi docker() { return docker; }

	public record Applied(FaultScenario scenario, List<String> targets, String detail) {}

	/** @return 실제로 영향을 준 노드 이름과 설명 */
	public Applied apply(FaultRequest req) {
		FaultScenario s = req.scenario();
		int delay = req.delayMs() == null ? 200 : req.delayMs();
		int loss = req.lossPct() == null ? 0 : req.lossPct();
		switch (s) {
			case STOP_PRIMARY, KILL_PRIMARY, PAUSE_PRIMARY, STOP_REPLICA, KILL_REPLICA, STOP_SENTINEL -> {
				String node = resolve(req.target(), s);
				String c = catalog.require(node).name();
				switch (s) {
					case STOP_PRIMARY, STOP_REPLICA, STOP_SENTINEL -> { docker.stop(c, 2); stoppedNodes.add(c); }
					case KILL_PRIMARY, KILL_REPLICA -> { docker.kill(c); stoppedNodes.add(c); }
					default -> { docker.pause(c); stoppedNodes.add(c); }
				}
				return new Applied(s, List.of(node), s.name().split("_")[0].toLowerCase() + " " + c);
			}
			case STOP_PRIMARY_AND_ITS_REPLICA -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				List<String> targets = new ArrayList<>(List.of(p));
				targets.addAll(topology.replicasOf(p));
				for (String t : targets) { docker.kill(t); stoppedNodes.add(t); }
				return new Applied(s, targets, "kill " + targets);
			}
			case STOP_ALL_SENTINELS -> {
				List<String> t = catalog.sentinels().stream().map(FaultCatalog.Node::name).toList();
				for (String n : t) { docker.stop(n, 2); stoppedNodes.add(n); }
				return new Applied(s, t, "stop " + t);
			}
			case RESTORE_NODE -> {
				String node = req.target() == null || req.target().isBlank() ? stoppedNodes.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("nothing to restore")) : catalog.require(req.target()).name();
				restore(node);
				return new Applied(s, List.of(node), "restored " + node);
			}
			case RESTORE_ALL -> { List<String> r = restoreAll(); return new Applied(s, r, "restored " + r); }
			case PARTITION_PRIMARY_FROM_SENTINELS, PARTITION_PRIMARY_FROM_REPLICAS, PARTITION_PRIMARY_FROM_APP, PARTITION_PRIMARY_FROM_PEERS -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				List<String> peers = switch (s) {
					case PARTITION_PRIMARY_FROM_SENTINELS -> catalog.sentinels().stream().map(FaultCatalog.Node::ip).toList();
					case PARTITION_PRIMARY_FROM_REPLICAS -> topology.replicasOf(p).stream().map(n -> catalog.require(n).ip()).toList();
					case PARTITION_PRIMARY_FROM_APP -> List.of(FaultCatalog.APP_IP);
					default -> catalog.redisNodes().stream().filter(n -> !n.name().equals(p)).map(FaultCatalog.Node::ip).toList();
				};
				List<String> cmd = new ArrayList<>(List.of("/faults/drop.sh", "add")); cmd.addAll(peers);
				exec(catalog.require(p).sidecar(), cmd);
				return new Applied(s, List.of(p), "drop on " + p + " ↔ " + peers);
			}
			case NETEM_REPLICATION_DELAY -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				List<String> cmd = new ArrayList<>(List.of("/faults/netem.sh", String.valueOf(delay), String.valueOf(loss)));
				cmd.addAll(topology.replicasOf(p).stream().map(n -> catalog.require(n).ip()).toList());
				exec(catalog.require(p).sidecar(), cmd);
				return new Applied(s, List.of(p), "netem " + delay + "ms/" + loss + "% " + p + " → replicas");
			}
			case NETEM_GLOBAL_DELAY, NETEM_LOSS -> {
				List<String> t = new ArrayList<>();
				for (var n : catalog.redisNodes()) { if (stoppedNodes.contains(n.name())) continue; exec(n.sidecar(), List.of("/faults/netem.sh", String.valueOf(s == FaultScenario.NETEM_LOSS ? 0 : delay), String.valueOf(s == FaultScenario.NETEM_LOSS ? Math.max(loss, 1) : loss))); t.add(n.name()); }
				return new Applied(s, t, "netem on all redis nodes");
			}
			case KILL_REPLICA_THEN_PRIMARY, KILL_ALL_REPLICAS_THEN_PRIMARY -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				List<String> reps = topology.replicasOf(p);
				if (reps.isEmpty()) throw new IllegalStateException("no replica of " + p);
				List<String> victims = s == FaultScenario.KILL_REPLICA_THEN_PRIMARY ? List.of(reps.get(0)) : reps;
				for (String r : victims) { docker.kill(r); stoppedNodes.add(r); }
				sleep(3000);
				docker.kill(p); stoppedNodes.add(p);
				List<String> all = new ArrayList<>(victims); all.add(p);
				return new Applied(s, List.of(p), "killed replicas " + victims + " then primary " + p + " (targets " + all + ")");
			}
			case STOP_SENTINELS_THEN_KILL_PRIMARY -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				var ss = catalog.sentinels();
				List<String> stopped = ss.subList(0, Math.min(2, ss.size())).stream().map(FaultCatalog.Node::name).toList();
				for (String n : stopped) { docker.stop(n, 2); stoppedNodes.add(n); }
				sleep(3000);
				docker.kill(p); stoppedNodes.add(p);
				return new Applied(s, List.of(p), "stopped sentinels " + stopped + " then killed primary " + p);
			}
			case KILL_TWO_PRIMARIES -> {
				List<String> prims = topology.primaries();
				if (prims.size() < 3) throw new IllegalStateException("needs 3 primaries, have " + prims);
				docker.kill(prims.get(1)); stoppedNodes.add(prims.get(1));
				sleep(500);
				docker.kill(prims.get(0)); stoppedNodes.add(prims.get(0));
				return new Applied(s, List.of(prims.get(0), prims.get(1)), "killed primaries " + prims.get(1) + " and " + prims.get(0) + " (majority lost)");
			}
			case NETEM_REPLICATION_DELAY_THEN_KILL_PRIMARY -> {
				String p = resolve(req.target(), FaultScenario.STOP_PRIMARY);
				List<String> cmd = new ArrayList<>(List.of("/faults/netem.sh", String.valueOf(delay), String.valueOf(loss)));
				cmd.addAll(topology.replicasOf(p).stream().map(n -> catalog.require(n).ip()).toList());
				exec(catalog.require(p).sidecar(), cmd);
				sleep(10_000); // 지연 상태에서 쓰기를 쌓아 offset 차이를 만든 뒤 죽인다
				docker.kill(p); stoppedNodes.add(p);
				dirtySidecars.remove(catalog.require(p).sidecar()); // 죽은 노드의 netns 는 사라짐
				return new Applied(s, List.of(p), "netem " + delay + "ms to replicas for 10s, then killed " + p);
			}
			case TOXIC_APP_SENTINEL_TIMEOUT -> { toxi.timeout(); return new Applied(s, List.of("toxiproxy"), "timeout toxic on sentinel proxies"); }
			case TOXIC_APP_SENTINEL_LATENCY -> { toxi.latency(delay); return new Applied(s, List.of("toxiproxy"), "latency " + delay + "ms on sentinel proxies"); }
			case REMOVE_TOXICS -> { toxi.removeAll(); return new Applied(s, List.of("toxiproxy"), "toxics removed"); }
			case CLEAR_NETWORK_FAULTS -> { List<String> c = clearNetwork(); return new Applied(s, c, "cleared " + c); }
		}
		throw new IllegalArgumentException("unsupported " + s);
	}

	public List<String> restoreAll() {
		List<String> restored = new ArrayList<>();
		for (var n : catalog.nodes()) {
			try {
				var st = docker.state(n.name());
				if (st.paused()) { docker.unpause(n.name()); restored.add(n.name()); }
				else if (!st.running()) { restore(n.name()); restored.add(n.name()); }
			} catch (RuntimeException e) { log.warn("restore {} failed: {}", n.name(), e.toString()); }
		}
		restored.addAll(clearNetwork());
		try { toxi.removeAll(); } catch (RuntimeException e) { log.warn("toxiproxy cleanup failed: {}", e.toString()); }
		stoppedNodes.clear();
		return restored;
	}

	private void restore(String node) {
		var st = docker.state(node);
		if (st.paused()) docker.unpause(node);
		else if (!st.running()) {
			docker.start(node);
			// 사이드카는 노드의 옛 네트워크 네임스페이스에 남아 있으므로 다시 붙인다.
			if (catalog.require(node).redis()) { try { docker.restart(catalog.require(node).sidecar()); } catch (RuntimeException e) { log.warn("sidecar restart failed: {}", e.toString()); } }
		}
		stoppedNodes.remove(node);
	}

	private List<String> clearNetwork() {
		List<String> cleared = new ArrayList<>();
		for (String sc : new ArrayList<>(dirtySidecars)) {
			try { docker.exec(sc, List.of("/faults/clear.sh")); cleared.add(sc); } catch (RuntimeException e) { log.warn("clear {} failed: {}", sc, e.toString()); }
			dirtySidecars.remove(sc);
		}
		return cleared;
	}

	private void exec(String sidecar, List<String> cmd) {
		dirtySidecars.add(sidecar);
		int code = docker.exec(sidecar, cmd);
		if (code != 0) throw new IllegalStateException("sidecar " + sidecar + " " + cmd + " exit " + code);
	}

	/** primary | primary-N | replica-N | sentinel-N | <catalog node name> */
	String resolve(String target, FaultScenario s) {
		String t = target == null || target.isBlank() ? (s == FaultScenario.STOP_SENTINEL ? "sentinel-1" : s.name().contains("REPLICA") && !s.name().startsWith("STOP_PRIMARY") ? "replica-1" : "primary") : target.trim();
		if (catalog.byName(t).isPresent()) return t;
		List<String> prims = topology.primaries();
		if (prims.isEmpty()) throw new IllegalStateException("no primary known yet");
		if (t.equals("primary")) return prims.get(0);
		if (t.startsWith("primary-")) return prims.get(index(t, prims.size()));
		if (t.startsWith("replica-")) {
			List<String> reps = new ArrayList<>();
			for (String p : prims) reps.addAll(topology.replicasOf(p));
			if (reps.isEmpty()) throw new IllegalStateException("no replica known");
			return reps.get(index(t, reps.size()));
		}
		if (t.startsWith("sentinel-")) { var ss = catalog.sentinels(); return ss.get(index(t, ss.size())).name(); }
		throw new IllegalArgumentException("target not allowed: " + target);
	}

	private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

	private static int index(String t, int size) {
		int i = Integer.parseInt(t.substring(t.indexOf('-') + 1)) - 1;
		if (i < 0 || i >= size) throw new IllegalArgumentException("target out of range: " + t);
		return i;
	}
}

package com.seongmin.redislab.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seongmin.redislab.chat.ChatStore;
import com.seongmin.redislab.config.LabProperties;
import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.fault.FaultExecutor;
import com.seongmin.redislab.fault.FaultRequest;
import com.seongmin.redislab.fault.FaultScenario;
import com.seongmin.redislab.metrics.LabMetrics;
import com.seongmin.redislab.record.Ledger;
import com.seongmin.redislab.record.RecordingService;
import com.seongmin.redislab.topology.FaultCatalog;
import com.seongmin.redislab.topology.TopologyWatcher;
import com.seongmin.redislab.workload.OpExecutor;
import com.seongmin.redislab.workload.WorkloadConfig;
import com.seongmin.redislab.workload.WorkloadRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 실험 수명주기: 생성(설정 적용·초기화·워크로드) → 장애 주입(T0, 자동 복구 타이머) → 이벤트로 T1~T6 도출 → 복구 → 종료(정합성 대조·요약).
 * 동시에 한 실험만. 위험 동작은 confirm=true 가 있어야 한다.
 */
@Service
@Profile("local-experiment")
public class ExperimentService {

	private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);
	private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
	private final AtomicReference<Experiment> active = new AtomicReference<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "fault-timer"); t.setDaemon(true); return t; });
	private volatile ScheduledFuture<?> autoRecover;
	private final FaultExecutor faults;
	private final TopologyWatcher topology;
	private final FaultCatalog catalog;
	private final WorkloadRunner workload;
	private final RecordingService recording;
	private final Ledger ledger;
	private final OpExecutor executor;
	private final ChatStore store;
	private final ConsistencyVerifier verifier;
	private final LabEventBus bus;
	private final LabMetrics metrics;
	private final LabProperties lab;
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public ExperimentService(FaultExecutor faults, TopologyWatcher topology, FaultCatalog catalog, WorkloadRunner workload, RecordingService recording, Ledger ledger, OpExecutor executor,
			ChatStore store, ConsistencyVerifier verifier, LabEventBus bus, LabMetrics metrics, LabProperties lab, JdbcTemplate jdbc, ObjectMapper json) {
		this.faults = faults; this.topology = topology; this.catalog = catalog; this.workload = workload; this.recording = recording; this.ledger = ledger; this.executor = executor;
		this.store = store; this.verifier = verifier; this.bus = bus; this.metrics = metrics; this.lab = lab; this.jdbc = jdbc; this.json = json;
		bus.subscribe(this::onEvent);
		recording.setResultHook(this::onResult);
	}

	public record CreateRequest(String name, String hypothesis, RecordingService.Mode recordingMode, WorkloadConfig workload, Map<String, String> redisSettings, Boolean startWorkload, Boolean flush) {}

	public synchronized Experiment create(CreateRequest req) {
		if (active.get() != null && !"DONE".equals(active.get().status)) throw new IllegalStateException("experiment " + active.get().id + " is still active; finish it first");
		Experiment e = new Experiment();
		e.id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Seoul")).format(Instant.now()) + "-" + lab.topology();
		e.name = req.name() == null ? e.id : req.name();
		e.topology = lab.topology();
		e.hypothesis = req.hypothesis();
		e.recordingMode = req.recordingMode() == null ? RecordingService.Mode.MEMORY : req.recordingMode();
		e.workload = req.workload();
		if (req.redisSettings() != null) req.redisSettings().forEach((k, v) -> { if (ALLOWED_SETTINGS.contains(k)) { topology.configSetAll(k, v); e.redisSettings.put(k, v); } });
		if (workload.running()) workload.stop();
		if (req.flush() == null || req.flush()) { try { store.flushAll(); } catch (RuntimeException ex) { log.warn("flush failed: {}", ex.toString()); } }
		ledger.reset();
		executor.resetSequence();
		recording.configure(e.recordingMode, e.id);
		recording.setPhase("BEFORE");
		e.status = "RUNNING";
		experiments.put(e.id, e);
		active.set(e);
		persist(e);
		if (req.workload() != null && (req.startWorkload() == null || req.startWorkload())) workload.start(req.workload());
		addEvent(e, "experiment", "CREATED", e.name);
		return e;
	}

	static final Set<String> ALLOWED_SETTINGS = Set.of("min-replicas-to-write", "min-replicas-max-lag", "appendonly", "appendfsync", "repl-backlog-size");

	public synchronized Experiment injectFailure(String id, FaultRequest req) {
		Experiment e = require(id);
		if (!Boolean.TRUE.equals(req.confirm())) throw new IllegalArgumentException("confirm=true is required for fault injection");
		if (e.t0 != null && !req.scenario().isRestore() && e.recoveredAt == null) throw new IllegalStateException("a fault is already active; recover first");
		String primaryBefore = topology.primaries().isEmpty() ? null : topology.primaries().get(0);
		FaultExecutor.Applied applied = faults.apply(req);
		Instant now = Instant.now();
		if (!req.scenario().isRestore()) {
			e.fault = req;
			e.t0 = now;
			e.t1 = e.t2 = e.t3 = e.t4 = e.t5 = e.t6 = null;
			e.recoveredAt = e.recoveredStableAt = null;
			e.failedNode = applied.targets().isEmpty() ? primaryBefore : applied.targets().get(0);
			e.failedShard = Math.max(0, topology.primaries().indexOf(e.failedNode));
			e.status = "FAULT";
			ledger.faultInjected = true;
			recording.setPhase("DURING");
			metrics.count("redis_failover_total", "topology", e.topology, "scenario", req.scenario().name());
			if (autoRecover != null) autoRecover.cancel(false);
			autoRecover = timer.schedule(() -> { try { log.warn("auto recover after {}s", lab.maxFaultSeconds()); recover(id); } catch (RuntimeException ex) { log.error("auto recover failed", ex); } }, lab.maxFaultSeconds(), TimeUnit.SECONDS);
		}
		addEvent(e, "fault", req.scenario().name(), applied.detail());
		jdbc.update("INSERT INTO fault_action (experiment_id, at, scenario, target, params, result) VALUES (?,?,?,?,?,?)", e.id, Timestamp.from(now), req.scenario().name(), String.valueOf(req.target()), toJson(req), applied.detail());
		bus.publish(LabEvent.of("fault", "experiment", req.scenario().name(), Map.of("experimentId", e.id, "targets", applied.targets(), "detail", applied.detail(), "t0", String.valueOf(e.t0))));
		persist(e);
		return e;
	}

	public synchronized Experiment recover(String id) {
		Experiment e = require(id);
		if (autoRecover != null) autoRecover.cancel(false);
		e.recoveredAt = Instant.now(); // 복구를 시작한 시각. 이후의 STABLE 이 재합류 완료(recoveredStableAt)다.
		List<String> restored = faults.restoreAll();
		e.status = "RECOVERED";
		recording.setPhase("AFTER");
		addEvent(e, "fault", "RESTORE_ALL", "restored " + restored);
		bus.publish(LabEvent.of("fault", "experiment", "RESTORE_ALL", Map.of("experimentId", e.id, "restored", restored)));
		persist(e);
		return e;
	}

	public Experiment setPhase(String id, String phase) {
		Experiment e = require(id);
		if (!Set.of("WARMUP", "BEFORE", "DURING", "AFTER").contains(phase)) throw new IllegalArgumentException("phase must be WARMUP|BEFORE|DURING|AFTER");
		recording.setPhase(phase);
		addEvent(e, "experiment", "PHASE", phase);
		return e;
	}

	public synchronized Experiment finish(String id) {
		Experiment e = require(id);
		if (workload.running()) workload.stop();
		try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
		recording.setPhase("VERIFY");
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("timings", e.timings());
		try { summary.put("consistency", verifier.verify()); } catch (RuntimeException ex) { summary.put("consistency_error", ex.toString()); }
		summary.put("phases", phaseStats(e.id));
		if (e.t0 != null) summary.put("log_timings", logTimings(e));
		summary.put("recordingDropped", recording.dropped());
		summary.put("failedNode", e.failedNode);
		summary.put("fault", e.fault);
		summary.put("redisSettings", e.redisSettings);
		e.summary = summary;
		e.status = "DONE";
		e.finishedAt = Instant.now();
		recording.setPhase("IDLE");
		recording.configure(RecordingService.Mode.MEMORY, null);
		persist(e);
		bus.publish(LabEvent.of("event", "experiment", "DONE", Map.of("experimentId", e.id)));
		return e;
	}

	/** Redis 노드 로그에서 서버 측 시각을 뽑는다(폴링 200 ms 오차 보정용). 앱·Redis 가 같은 VM 시계를 쓴다. */
	private Map<String, Object> logTimings(Experiment e) {
		Map<String, Object> m = new LinkedHashMap<>();
		long since = e.t0.getEpochSecond() - 1;
		java.util.regex.Pattern ts = java.util.regex.Pattern.compile("^(\\S+Z) (.*)$");
		record Hit(String key, String pattern) {}
		List<Hit> hits = List.of(new Hit("t1_log_fail_quorum", "as failing (quorum reached)"), new Hit("t2_log_election_delayed", "Start of election delayed"),
				new Hit("t2_log_election_start", "Starting a failover election"), new Hit("t3_log_election_won", "Failover election won"),
				new Hit("t3_log_master_mode", "MASTER MODE enabled"), new Hit("rejoin_log_reconfigure", "Reconfiguring myself as a replica"),
				new Hit("rejoin_log_turning_replica", "Before turning into a replica"), new Hit("rejoin_log_partial_resync", "Partial resynchronization accepted"),
				new Hit("rejoin_log_full_resync", "Full resync"), new Hit("rejoin_log_partial_rejected", "Partial resynchronization not accepted"),
				new Hit("rejoin_log_sync_done", "MASTER <-> REPLICA sync: Finished with success"), new Hit("log_clusterdown_writes", "CLUSTERDOWN"));
		for (var n : catalog.redisNodes()) {
			for (String line : faults.docker().logs(n.name(), since)) {
				var mt = ts.matcher(line.trim());
				if (!mt.matches()) continue;
				for (Hit h : hits) {
					if (!mt.group(2).contains(h.pattern())) continue;
					String key = h.key();
					boolean rejoin = key.startsWith("rejoin");
					Instant at;
					try { at = Instant.parse(mt.group(1)); } catch (Exception ex) { continue; }
					if (rejoin && (e.recoveredAt == null || at.isBefore(e.recoveredAt))) continue;
					if (!rejoin && at.isBefore(e.t0)) continue;
					m.putIfAbsent(key, Map.of("at", at.toString(), "node", n.name(), "line", mt.group(2).length() > 160 ? mt.group(2).substring(0, 160) : mt.group(2)));
				}
			}
		}
		return m;
	}

	/** 단계별(BEFORE/DURING/AFTER) 집계: 요청 수, 실패 수, 오류율, p95/p99 중앙값, 초당 처리량. */
	private Map<String, Object> phaseStats(String id) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String phase : List.of("BEFORE", "DURING", "AFTER")) {
			var rows = jdbc.queryForList("SELECT ok, fail, p95_us, p99_us, at FROM metric_sample WHERE experiment_id = ? AND phase = ? ORDER BY at", id, phase);
			if (rows.isEmpty()) continue;
			long ok = 0, fail = 0; List<Integer> p95 = new ArrayList<>(), p99 = new ArrayList<>();
			for (var r : rows) { ok += ((Number) r.get("ok")).longValue(); fail += ((Number) r.get("fail")).longValue(); p95.add(((Number) r.get("p95_us")).intValue()); p99.add(((Number) r.get("p99_us")).intValue()); }
			Collections.sort(p95); Collections.sort(p99);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("seconds", rows.size()); m.put("ok", ok); m.put("fail", fail);
			m.put("error_rate_pct", ok + fail == 0 ? 0 : Math.round(fail * 10000.0 / (ok + fail)) / 100.0);
			m.put("tps", Math.round((ok + fail) * 10.0 / rows.size()) / 10.0); m.put("success_tps", Math.round(ok * 10.0 / rows.size()) / 10.0);
			m.put("p95_ms_median", p95.get(p95.size() / 2) / 1000.0); m.put("p99_ms_median", p99.get(p99.size() / 2) / 1000.0);
			m.put("p95_ms_max", p95.get(p95.size() - 1) / 1000.0);
			out.put(phase, m);
		}
		return out;
	}

	// ---------- T1~T6 도출 ----------
	private void onEvent(LabEvent ev) {
		Experiment e = active.get();
		if (e == null || e.t0 == null || "DONE".equals(e.status) || "topology".equals(ev.kind()) || "sample".equals(ev.kind())) return;
		if (ev.at().isBefore(e.t0)) return;
		String src = ev.source(), type = ev.type();
		String detail = ev.payload() == null ? "" : String.valueOf(ev.payload());
		boolean record = true;
		if (src.startsWith("sentinel:")) {
			if (e.t1 == null && ("+odown".equals(type))) e.t1 = ev.at();
			if (e.t2 == null && ("+try-failover".equals(type) || "+failover-state-select-slave".equals(type))) e.t2 = ev.at();
			if (e.t3 == null && ("+promoted-slave".equals(type) || "+switch-master".equals(type))) e.t3 = ev.at();
			if (e.tSwitchMaster == null && "+switch-master".equals(type)) e.tSwitchMaster = ev.at();
			record = !type.startsWith("-") || type.equals("-odown") || type.equals("-sdown");
		} else if ("watcher".equals(src)) {
			switch (type) {
				case "FAIL" -> { if (e.t1 == null && detail.contains(String.valueOf(e.failedNode))) e.t1 = ev.at(); }
				case "EPOCH" -> { if (e.t2 == null) e.t2 = ev.at(); }
				case "PROMOTED" -> { if (e.t3 == null) e.t3 = ev.at(); }
				case "PRIMARY_CHANGED" -> { if (e.t3 == null && !"cluster".equals(e.topology) && !detail.contains("to=null")) e.t3 = ev.at(); }
				case "STABLE" -> { if (e.t3 != null && e.t6 == null && e.recoveredAt == null) e.t6 = ev.at(); if (e.recoveredAt != null && ev.at().isAfter(e.recoveredAt)) e.recoveredStableAt = ev.at(); }
				case "NODE_STATUS" -> {
					// 재기동한 노드가 바로 REPLICA|UP 으로 합류해 불안정 구간이 없으면 그 시각을 재합류 완료로 본다.
					if (e.recoveredAt != null && e.recoveredStableAt == null && ev.at().isAfter(e.recoveredAt) && detail.contains("to=REPLICA|UP") && topology.snapshot().stable()) e.recoveredStableAt = ev.at();
				}
				case "UNSTABLE", "STATE", "PFAIL", "DEMOTED" -> {}
				default -> record = false;
			}
		} else if ("lettuce".equals(src)) {
			// T4 = 앱이 "새 Primary" 에 실제로 연결된 시각. Sentinel 에 붙는 연결(26379)은 제외한다.
			if (e.t4 == null && "ClusterTopologyChangedEvent".equals(type)) e.t4 = ev.at();
			if (e.t4 == null && "ConnectedEvent".equals(type) && e.t3 != null && !topology.primaries().isEmpty()) {
				String ip = catalog.byName(topology.primaries().get(0)).map(n -> n.ip() + ":" + n.port()).orElse("-");
				if (detail.contains(ip) && !topology.primaries().get(0).equals(e.failedNode)) e.t4 = ev.at();
			}
		} else if ("experiment".equals(src) || "fault".equals(ev.kind()) || "workload".equals(ev.kind())) {
			record = false; // 이미 addEvent 로 기록됨
		}
		if (record) addEvent(e, src, type, detail.length() > 400 ? detail.substring(0, 400) : detail);
		if (e.t3 != null && e.t6 != null && e.summary.isEmpty()) {
			Map<String, Object> t = e.timings();
			if (t.get("detect_ms") != null) metrics.failoverPhase(e.topology, "detect", (Long) t.get("detect_ms"));
			if (t.get("promote_ms") != null) metrics.failoverPhase(e.topology, "promote", (Long) t.get("promote_ms"));
			if (t.get("outage_ms") != null) metrics.failoverPhase(e.topology, "total", (Long) t.get("outage_ms"));
		}
	}

	private void onResult(com.seongmin.redislab.workload.Result r) {
		Experiment e = active.get();
		if (e == null || e.t0 == null || e.t5 != null || !r.ok() || !r.request().op().write) return;
		if (r.request().shard() != e.failedShard || r.ackedAt().isBefore(e.t0)) return;
		e.t5 = r.ackedAt();
		addEvent(e, "workload", "FIRST_WRITE_OK", r.request().op() + " " + r.request().key() + " seq=" + r.request().seq());
	}

	private void addEvent(Experiment e, String src, String type, String detail) {
		Experiment.TimelineEvent te = new Experiment.TimelineEvent(Instant.now(), src, type, detail);
		synchronized (e.events) { e.events.add(te); }
		try { jdbc.update("INSERT INTO experiment_event (experiment_id, at, source, type, detail) VALUES (?,?,?,?,?)", e.id, Timestamp.from(te.at()), src, type, detail.length() > 500 ? detail.substring(0, 500) : detail); }
		catch (RuntimeException ex) { log.debug("event insert failed: {}", ex.toString()); }
	}

	// ---------- 조회 ----------
	public Experiment require(String id) {
		Experiment e = experiments.get(id);
		if (e == null) throw new NoSuchElementException("experiment not found: " + id);
		return e;
	}

	public Optional<Experiment> active() { return Optional.ofNullable(active.get()); }

	public List<Map<String, Object>> list() {
		var rows = jdbc.queryForList("SELECT id, name, topology, status, recording_mode, created_at, t0, t5, finished_at, summary FROM experiment ORDER BY created_at DESC LIMIT 200");
		for (var r : rows) { r.put("recordingMode", r.remove("recording_mode")); r.put("summary", parseJson(r.get("summary"))); }
		return rows;
	}

	/** 메모리에 없으면(앱 재시작 후) DB 행으로 같은 모양을 복원한다. */
	public Map<String, Object> view(String id) {
		Experiment cached = experiments.get(id);
		if (cached != null) return toMap(cached);
		final Experiment e;
		{
			var rows = jdbc.queryForList("SELECT * FROM experiment WHERE id = ?", id);
			if (rows.isEmpty()) throw new NoSuchElementException("experiment not found: " + id);
			var r = rows.get(0);
			e = new Experiment();
			e.id = id; e.name = (String) r.get("name"); e.topology = (String) r.get("topology"); e.hypothesis = (String) r.get("hypothesis"); e.status = (String) r.get("status");
			e.recordingMode = RecordingService.Mode.valueOf((String) r.get("recording_mode"));
			e.workload = json.convertValue(parseJson(r.get("workload")), WorkloadConfig.class);
			Object rs = parseJson(r.get("redis_settings"));
			if (rs instanceof Map<?, ?> mm) mm.forEach((k, v) -> e.redisSettings.put(String.valueOf(k), String.valueOf(v)));
			e.createdAt = inst(r.get("created_at")); e.t0 = inst(r.get("t0")); e.t1 = inst(r.get("t1")); e.t2 = inst(r.get("t2")); e.t3 = inst(r.get("t3"));
			e.t4 = inst(r.get("t4")); e.t5 = inst(r.get("t5")); e.t6 = inst(r.get("t6")); e.recoveredAt = inst(r.get("recovered_at")); e.finishedAt = inst(r.get("finished_at"));
			Object sm = parseJson(r.get("summary"));
			if (sm instanceof Map<?, ?> mm) { mm.forEach((k, v) -> e.summary.put(String.valueOf(k), v)); e.failedNode = (String) e.summary.get("failedNode"); }
			for (var ev : jdbc.queryForList("SELECT at, source, type, detail FROM experiment_event WHERE experiment_id = ? ORDER BY at, id", id))
				e.events.add(new Experiment.TimelineEvent(inst(ev.get("at")), (String) ev.get("source"), (String) ev.get("type"), (String) ev.get("detail")));
		}
		return toMap(e);
	}

	private Object parseJson(Object v) { try { return v == null ? null : json.readValue(String.valueOf(v), Object.class); } catch (Exception e) { return v; } }
	private static Instant inst(Object v) { return v instanceof Timestamp t ? t.toInstant() : v instanceof java.time.LocalDateTime l ? l.atZone(ZoneId.of("Asia/Seoul")).toInstant() : null; }

	public List<Map<String, Object>> samples(String id) { return jdbc.queryForList("SELECT at, phase, ok, fail, p50_us, p95_us, p99_us, max_us, by_error FROM metric_sample WHERE experiment_id = ? ORDER BY at", id); }

	public Map<String, Object> toMap(Experiment e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", e.id); m.put("name", e.name); m.put("topology", e.topology); m.put("hypothesis", e.hypothesis); m.put("status", e.status);
		m.put("recordingMode", e.recordingMode); m.put("workload", e.workload); m.put("redisSettings", e.redisSettings); m.put("fault", e.fault);
		m.put("failedNode", e.failedNode); m.put("failedShard", e.failedShard); m.put("createdAt", e.createdAt); m.put("finishedAt", e.finishedAt);
		m.put("timings", e.timings());
		synchronized (e.events) { m.put("events", List.copyOf(e.events)); }
		m.put("summary", e.summary);
		return m;
	}

	private void persist(Experiment e) {
		try {
			jdbc.update("""
					INSERT INTO experiment (id, name, topology, hypothesis, status, recording_mode, workload, redis_settings, created_at, t0, t1, t2, t3, t4, t5, t6, recovered_at, finished_at, summary)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					ON DUPLICATE KEY UPDATE status=VALUES(status), t0=VALUES(t0), t1=VALUES(t1), t2=VALUES(t2), t3=VALUES(t3), t4=VALUES(t4), t5=VALUES(t5), t6=VALUES(t6),
					recovered_at=VALUES(recovered_at), finished_at=VALUES(finished_at), summary=VALUES(summary)""",
					e.id, e.name, e.topology, e.hypothesis, e.status, e.recordingMode.name(), toJson(e.workload), toJson(e.redisSettings), ts(e.createdAt), ts(e.t0), ts(e.t1), ts(e.t2), ts(e.t3), ts(e.t4), ts(e.t5), ts(e.t6), ts(e.recoveredAt), ts(e.finishedAt), toJson(e.summary));
		} catch (RuntimeException ex) { log.warn("experiment persist failed: {}", ex.toString()); }
	}

	/** 종료 시 T1~T6 도 저장되도록 주기적으로 덮어쓴다. */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 2000)
	public void persistActive() { Experiment e = active.get(); if (e != null && !"DONE".equals(e.status)) persist(e); }

	private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
	private String toJson(Object o) { try { return o == null ? null : json.writeValueAsString(o); } catch (Exception e) { return null; } }
}

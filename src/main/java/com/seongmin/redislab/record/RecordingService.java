package com.seongmin.redislab.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import com.seongmin.redislab.workload.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 기록 방식 3종. MEMORY: 초 집계만 저장. BATCH: 요청마다 행을 큐에 넣고 배치 INSERT(정합성 모드 고정).
 * SAMPLED: 1/100 요청만 행 저장 + 초 집계. 어느 방식이든 Redis 호출 스레드는 DB 를 기다리지 않는다.
 */
@Service
public class RecordingService {

	public enum Mode { MEMORY, BATCH, SAMPLED }

	private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
	private final JdbcTemplate jdbc;
	private final LabEventBus bus;
	private final ObjectMapper json;
	private final Ledger ledger;
	private final SecondAggregator agg = new SecondAggregator();
	private final ArrayBlockingQueue<Result> queue = new ArrayBlockingQueue<>(200_000);
	private volatile Mode mode = Mode.MEMORY;
	private volatile String experimentId;
	private volatile String phase = "IDLE";
	private volatile Sample lastSample;
	private volatile long dropped;
	private volatile java.util.function.Consumer<Result> resultHook;

	public RecordingService(JdbcTemplate jdbc, LabEventBus bus, ObjectMapper json, Ledger ledger) {
		this.jdbc = jdbc;
		this.bus = bus;
		this.json = json;
		this.ledger = ledger;
		Thread t = new Thread(this::drain, "record-writer");
		t.setDaemon(true);
		t.start();
	}

	public void configure(Mode m, String expId) { mode = m; experimentId = expId; }
	public void setPhase(String p) { phase = p; }
	public String phase() { return phase; }
	public String experimentId() { return experimentId; }
	public Mode mode() { return mode; }
	public Sample lastSample() { return lastSample; }
	public long dropped() { return dropped; }
	public void setResultHook(java.util.function.Consumer<Result> h) { resultHook = h; }

	public void record(Result r) {
		ledger.record(r);
		agg.add(r);
		var h = resultHook;
		if (h != null) h.accept(r);
		if (experimentId == null) return;
		if (mode == Mode.BATCH || (mode == Mode.SAMPLED && r.request().seq() % 100 == 0)) {
			if (!queue.offer(r)) dropped++;
		}
	}

	@Scheduled(fixedRate = 1000)
	public void flushSecond() {
		Sample s = agg.flush(phase);
		lastSample = s;
		bus.publish(LabEvent.of("sample", "recording", "second", s));
		if (experimentId == null || (s.ok() == 0 && s.fail() == 0)) return;
		try {
			jdbc.update("INSERT INTO metric_sample (experiment_id, at, phase, ok, fail, by_op, by_error, p50_us, p95_us, p99_us, max_us) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
					experimentId, Timestamp.from(s.at()), s.phase(), s.ok(), s.fail(), json.writeValueAsString(s.byOp()), json.writeValueAsString(s.byError()), s.p50Us(), s.p95Us(), s.p99Us(), s.maxUs());
		} catch (Exception e) {
			log.warn("metric_sample insert failed: {}", e.toString());
		}
	}

	private void drain() {
		List<Result> batch = new ArrayList<>(500);
		while (true) {
			try {
				Result first = queue.poll(200, TimeUnit.MILLISECONDS);
				if (first == null) continue;
				batch.add(first);
				queue.drainTo(batch, 499);
				jdbc.batchUpdate("INSERT INTO request_log (experiment_id, request_id, seq, op, k, v, requested_at, acked_at, status, latency_us, result_text, shard) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
						batch, batch.size(), (ps, r) -> {
							var q = r.request();
							ps.setString(1, q.experimentId() == null ? "-" : q.experimentId()); ps.setString(2, q.requestId()); ps.setLong(3, q.seq()); ps.setString(4, q.op().name());
							ps.setString(5, q.key()); ps.setString(6, q.value()); ps.setTimestamp(7, Timestamp.from(q.requestedAt()));
							ps.setTimestamp(8, r.ok() ? Timestamp.from(r.ackedAt()) : null); ps.setString(9, r.outcome().name());
							ps.setInt(10, (int) Math.min(Integer.MAX_VALUE, r.latencyNanos() / 1000)); ps.setString(11, r.resultText()); ps.setInt(12, q.shard());
						});
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				log.warn("request_log batch failed ({} rows): {}", batch.size(), e.toString());
			} finally {
				batch.clear();
			}
		}
	}
}

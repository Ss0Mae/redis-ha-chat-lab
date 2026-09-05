package com.seongmin.redislab.workload;

import com.seongmin.redislab.chat.ChatStore;
import com.seongmin.redislab.chat.Keys;
import com.seongmin.redislab.chat.Op;
import com.seongmin.redislab.chat.Outcome;
import com.seongmin.redislab.config.LabProperties;
import com.seongmin.redislab.metrics.LabMetrics;
import com.seongmin.redislab.record.RecordingService;
import com.seongmin.redislab.topology.ShardResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 워크로드·k6 HTTP 양쪽이 지나는 단일 실행 경로. 순번 부여 → 실행 → 분류 → (재시도) → 기록.
 */
@Component
public class OpExecutor {

	private final ChatStore store;
	private final RecordingService recording;
	private final LabMetrics metrics;
	private final ShardResolver shards;
	private final String retry;
	private final int waitReplicas;
	private final long waitTimeoutMs;
	private final AtomicLong seq = new AtomicLong();
	private final AtomicLong otherLogged = new AtomicLong();
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpExecutor.class);

	public OpExecutor(ChatStore store, RecordingService recording, LabMetrics metrics, ShardResolver shards, LabProperties lab) {
		this.store = store;
		this.recording = recording;
		this.metrics = metrics;
		this.shards = shards;
		this.retry = lab.retry() == null ? "none" : lab.retry();
		this.waitReplicas = lab.waitReplicas() == null ? 0 : lab.waitReplicas();
		this.waitTimeoutMs = lab.waitTimeoutMs() == null ? 1000 : lab.waitTimeoutMs();
	}

	public void resetSequence() { seq.set(0); otherLogged.set(0); }

	public Result execute(Op op, long id, boolean hashTag, int mgetSize) {
		long s = seq.incrementAndGet();
		String key = switch (op) {
			case GET, SET -> Keys.presence(id);
			case INCR -> Keys.unread(id);
			case SEND -> Keys.roomStream(id, hashTag);
			case MGET -> Keys.presence(id);
			case JOIN -> Keys.roomSlots(id);
		};
		String value = op == Op.SET ? String.valueOf(s) : op == Op.SEND ? "m" + s : null;
		Request req = new Request(recording.experimentId(), UUID.randomUUID().toString(), s, op, id, key, value, shards.shardOf(key), Instant.now());
		long t0 = System.nanoTime();
		Result r = run(req, hashTag, mgetSize, false, t0);
		if (!r.ok() && canRetry(op, r.outcome())) {
			try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
			metrics.count("redis_retry_total", "op", op.name());
			r = run(req, hashTag, mgetSize, true, t0);
		}
		metrics.command(op.name(), r.outcome().name(), r.latencyNanos());
		recording.record(r);
		return r;
	}

	private Result run(Request req, boolean hashTag, int mgetSize, boolean retried, long t0) {
		String text = null;
		Outcome outcome = Outcome.OK;
		try {
			switch (req.op()) {
				case GET -> text = store.getPresence(req.id());
				case SET -> store.setPresence(req.id(), req.value());
				case INCR -> text = String.valueOf(store.incrUnread(req.id()));
				case SEND -> { long[] r = store.sendMessage(req.id(), req.requestId(), req.value(), hashTag); text = r[0] + (r[1] == 0 ? " (dedupe)" : ""); }
				case MGET -> { List<Long> ids = new ArrayList<>(); for (int i = 0; i < mgetSize; i++) ids.add(req.id() + i); text = String.valueOf(store.presenceOf(ids).size()); }
				case JOIN -> text = String.valueOf(store.join(req.id(), req.seq()));
			}
			// WAIT: 쓰기가 n개 Replica 에 전달됐을 때만 "승인" 으로 본다. 시간 안에 못 채우면 실패로 분류(장부에 안 올림).
			if (waitReplicas > 0 && req.op().write) {
				long acked = store.waitReplicas(req.key(), waitReplicas, waitTimeoutMs);
				if (acked < waitReplicas) { outcome = Outcome.WAIT_TIMEOUT; text = "WAIT acked " + acked + "/" + waitReplicas; }
			}
		} catch (RuntimeException e) {
			outcome = Outcome.classify(e);
			Throwable root = e;
			while (root.getCause() != null && root.getCause() != root) root = root.getCause();
			text = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse(""))
					+ (root != e ? " <- " + root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()).lines().findFirst().orElse("") : "");
			if (outcome == Outcome.OTHER && otherLogged.incrementAndGet() <= 5) log.warn("OTHER outcome for {} {}", req.op(), req.key(), e);
		}
		return new Result(req, outcome, System.nanoTime() - t0, Instant.now(), text == null ? null : (text.length() > 110 ? text.substring(0, 110) : text), retried);
	}

	private boolean canRetry(Op op, Outcome o) {
		if (o != Outcome.TIMEOUT && o != Outcome.CONNECTION) return false;
		return switch (retry) { case "all" -> true; case "reads" -> !op.write; default -> false; };
	}
}

package com.seongmin.redislab.workload;

import com.seongmin.redislab.chat.Op;
import com.seongmin.redislab.events.LabEvent;
import com.seongmin.redislab.events.LabEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** 토큰 버킷(10 ms 틱)으로 초당 rps 건을 고르게 발생시킨다. 실행은 가상 스레드. */
@Component
public class WorkloadRunner {

	private static final Logger log = LoggerFactory.getLogger(WorkloadRunner.class);
	private final OpExecutor executor;
	private final LabEventBus bus;
	private volatile WorkloadConfig config;
	private volatile Thread ticker;
	private volatile ExecutorService pool;
	private volatile Instant startedAt;
	private final AtomicLong submitted = new AtomicLong();

	public WorkloadRunner(OpExecutor executor, LabEventBus bus) {
		this.executor = executor;
		this.bus = bus;
	}

	public synchronized void start(WorkloadConfig cfg) {
		if (ticker != null) throw new IllegalStateException("workload already running");
		config = cfg;
		submitted.set(0);
		startedAt = Instant.now();
		pool = Executors.newVirtualThreadPerTaskExecutor();
		Thread t = new Thread(this::loop, "workload-ticker");
		t.setDaemon(true);
		ticker = t;
		t.start();
		bus.publish(LabEvent.of("workload", "workload", "started", cfg));
		log.info("workload started {}", cfg);
	}

	public synchronized void stop() {
		Thread t = ticker;
		if (t == null) return;
		ticker = null;
		t.interrupt();
		pool.shutdown();
		bus.publish(LabEvent.of("workload", "workload", "stopped", Map.of("submitted", submitted.get())));
		log.info("workload stopped after {} requests", submitted.get());
	}

	public boolean running() { return ticker != null; }

	public Map<String, Object> status() {
		return Map.of("running", running(), "config", config == null ? Map.of() : config, "startedAt", startedAt == null ? "" : startedAt.toString(), "submitted", submitted.get());
	}

	private void loop() {
		double tokens = 0;
		long next = System.nanoTime();
		while (ticker == Thread.currentThread()) {
			WorkloadConfig cfg = config;
			tokens += cfg.rps() / 100.0;
			while (tokens >= 1) {
				tokens -= 1;
				submitted.incrementAndGet();
				pool.submit(() -> one(cfg));
			}
			next += 10_000_000L;
			long sleep = next - System.nanoTime();
			if (sleep > 0) { try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; } }
			else next = System.nanoTime(); // 밀리면 따라잡지 않고 재정렬(버스트 방지)
		}
	}

	private void one(WorkloadConfig cfg) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int pick = rnd.nextInt(cfg.mix().total());
		Op op;
		var m = cfg.mix();
		if ((pick -= m.get()) < 0) op = Op.GET;
		else if ((pick -= m.set()) < 0) op = Op.SET;
		else if ((pick -= m.incr()) < 0) op = Op.INCR;
		else if ((pick -= m.send()) < 0) op = Op.SEND;
		else op = Op.MGET;
		long id = op == Op.SEND ? 1 + rnd.nextInt(cfg.rooms()) : 1 + rnd.nextInt(cfg.users());
		executor.execute(op, id, cfg.hashTag(), cfg.mgetSize());
	}
}

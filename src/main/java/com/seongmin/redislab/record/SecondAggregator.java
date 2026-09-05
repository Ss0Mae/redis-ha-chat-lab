package com.seongmin.redislab.record;

import com.seongmin.redislab.workload.Result;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** 락 없는 초 단위 집계. flush 때 지연 배열을 정렬해 백분위를 낸다(초당 수천 건이라 충분). */
public class SecondAggregator {

	private final Map<String, LongAdder> byOp = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> byError = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> byShard = new ConcurrentHashMap<>(); // Cluster: slot 담당 Primary 순번별 성공/실패
	private final LongAdder ok = new LongAdder(), fail = new LongAdder(), okWrites = new LongAdder(), failWrites = new LongAdder();
	private final ConcurrentLinkedQueue<Integer> latenciesUs = new ConcurrentLinkedQueue<>();

	public void add(Result r) {
		String opKey = r.request().op().name() + ":" + (r.ok() ? "ok" : "fail");
		byOp.computeIfAbsent(opKey, k -> new LongAdder()).increment();
		byShard.computeIfAbsent(r.request().shard() + ":" + (r.ok() ? "ok" : "fail"), k -> new LongAdder()).increment();
		if (r.ok()) { ok.increment(); if (r.request().op().write) okWrites.increment(); }
		else { fail.increment(); byError.computeIfAbsent(r.outcome().name(), k -> new LongAdder()).increment(); if (r.request().op().write) failWrites.increment(); }
		latenciesUs.add((int) Math.min(Integer.MAX_VALUE, r.latencyNanos() / 1000));
	}

	public Sample flush(String phase) {
		int[] lat = latenciesUs.stream().mapToInt(Integer::intValue).toArray();
		latenciesUs.clear();
		Arrays.sort(lat);
		Map<String, Integer> ops = new TreeMap<>(), errs = new TreeMap<>(), shards = new TreeMap<>();
		byOp.forEach((k, v) -> { int n = (int) v.sumThenReset(); if (n > 0) ops.put(k, n); });
		byError.forEach((k, v) -> { int n = (int) v.sumThenReset(); if (n > 0) errs.put(k, n); });
		byShard.forEach((k, v) -> { int n = (int) v.sumThenReset(); if (n > 0) shards.put(k, n); });
		return new Sample(Instant.now(), phase, (int) ok.sumThenReset(), (int) fail.sumThenReset(), ops, errs,
				pct(lat, 0.5), pct(lat, 0.95), pct(lat, 0.99), lat.length == 0 ? 0 : lat[lat.length - 1], (int) okWrites.sumThenReset(), (int) failWrites.sumThenReset(), shards);
	}

	private static int pct(int[] sorted, double q) {
		if (sorted.length == 0) return 0;
		return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(q * sorted.length) - 1))];
	}
}

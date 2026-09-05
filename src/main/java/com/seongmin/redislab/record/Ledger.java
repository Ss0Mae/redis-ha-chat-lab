package com.seongmin.redislab.record;

import com.seongmin.redislab.workload.Result;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 성공 응답(ACK)을 받은 쓰기의 메모리 장부. Failover 뒤 Redis 실제 값과 대조해 유실·중복을 센다.
 * presence: 사용자별 마지막 ACK 값(seq), unread: 사용자별 ACK 된 INCR 횟수, rooms: 방별 ACK 된 메시지 seq 집합.
 */
@Component
public class Ledger {

	public final Map<Long, Long> presence = new ConcurrentHashMap<>();
	public final Map<Long, LongAdder> unread = new ConcurrentHashMap<>();
	public final Map<Long, Set<Long>> rooms = new ConcurrentHashMap<>();
	public final AtomicLong ackedWrites = new AtomicLong();
	public final AtomicLong ackedWritesBeforeFault = new AtomicLong();
	public volatile boolean faultInjected;

	public void reset() { presence.clear(); unread.clear(); rooms.clear(); ackedWrites.set(0); ackedWritesBeforeFault.set(0); faultInjected = false; }

	public void record(Result r) {
		if (!r.ok() || !r.request().op().write) return;
		ackedWrites.incrementAndGet();
		if (!faultInjected) ackedWritesBeforeFault.incrementAndGet();
		long id = r.request().id();
		switch (r.request().op()) {
			case SET -> presence.merge(id, r.request().seq(), Math::max);
			case INCR -> unread.computeIfAbsent(id, k -> new LongAdder()).increment();
			case SEND -> {
				String t = r.resultText();
				if (t == null) return;
				int sp = t.indexOf(' ');
				long seq = Long.parseLong(sp < 0 ? t : t.substring(0, sp));
				rooms.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(seq);
			}
			default -> {}
		}
	}
}

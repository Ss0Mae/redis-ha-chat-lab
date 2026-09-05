package com.seongmin.redislab.experiment;

import com.seongmin.redislab.chat.ChatStore;
import com.seongmin.redislab.record.Ledger;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Failover 뒤 안정화된 Redis 의 실제 값과 ACK 장부를 대조한다.
 * 유실률(%) = 복구 후 존재하지 않는 승인 쓰기 수 / 장애 전 성공 응답을 받은 쓰기 수 × 100
 */
@Component
public class ConsistencyVerifier {

	private final ChatStore store;
	private final Ledger ledger;

	public ConsistencyVerifier(ChatStore store, Ledger ledger) { this.store = store; this.ledger = ledger; }

	public Map<String, Object> verify() {
		long ackedMsgs = 0, presentMsgs = 0, lostMsgs = 0, dupStream = 0, lastPreserved = 0, readErrors = 0;
		List<Long> lostSample = new ArrayList<>();
		for (var e : ledger.rooms.entrySet()) {
			List<Long> seqs;
			try { seqs = store.streamSeqs(e.getKey()); } catch (RuntimeException ex) { readErrors++; continue; }
			Map<Long, Integer> counts = new HashMap<>();
			for (Long s : seqs) counts.merge(s, 1, Integer::sum);
			for (int c : counts.values()) if (c > 1) dupStream += c - 1;
			for (Long s : e.getValue()) {
				ackedMsgs++;
				if (counts.containsKey(s)) { presentMsgs++; lastPreserved = Math.max(lastPreserved, s); }
				else { lostMsgs++; if (lostSample.size() < 20) lostSample.add(s); }
			}
		}
		long dupIncr = 0, lostIncr = 0, incrKeys = 0;
		for (var e : ledger.unread.entrySet()) {
			incrKeys++;
			long expected = e.getValue().sum();
			long actual;
			try { actual = parse(getRaw(e.getKey())); } catch (RuntimeException ex) { readErrors++; continue; }
			if (actual > expected) dupIncr += actual - expected;
			else if (actual < expected) lostIncr += expected - actual;
		}
		long wrongPresence = 0, presenceKeys = 0;
		for (var e : ledger.presence.entrySet()) {
			presenceKeys++;
			try {
				String v = store.getPresence(e.getKey());
				if (v == null || parse(v) < e.getValue()) wrongPresence++;
			} catch (RuntimeException ex) { readErrors++; }
		}
		long ackedWrites = ledger.ackedWrites.get(), before = ledger.ackedWritesBeforeFault.get();
		long lostTotal = lostMsgs + lostIncr + wrongPresence;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("acked_writes_total", ackedWrites);
		m.put("acked_writes_before_fault", before);
		m.put("acked_messages", ackedMsgs);
		m.put("acked_messages_present", presentMsgs);
		m.put("acked_messages_lost", lostMsgs);
		m.put("lost_message_seq_sample", lostSample);
		m.put("last_preserved_seq", lastPreserved);
		m.put("duplicate_stream_entries", dupStream);
		m.put("incr_keys", incrKeys);
		m.put("duplicate_incr", dupIncr);
		m.put("lost_incr", lostIncr);
		m.put("presence_keys", presenceKeys);
		m.put("wrong_value_keys", wrongPresence);
		m.put("lost_acked_writes", lostTotal);
		m.put("acked_write_loss_pct", before == 0 ? null : Math.round(lostTotal * 10000.0 / before) / 100.0);
		m.put("read_errors", readErrors);
		return m;
	}

	private String getRaw(long userId) { return ((com.seongmin.redislab.chat.RedisChatStore) store).rawGet(com.seongmin.redislab.chat.Keys.unread(userId)); }
	private static long parse(String v) { return v == null ? 0 : Long.parseLong(v.trim()); }
}

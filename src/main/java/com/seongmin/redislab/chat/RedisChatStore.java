package com.seongmin.redislab.chat;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class RedisChatStore implements ChatStore {

	/** 멱등 키 검사 + 순번 발급 + 저장을 한 스크립트로. 세 키가 같은 slot 에 있어야 Cluster 에서 실행된다. */
	static final String SEND_LUA = """
			local existing = redis.call('GET', KEYS[1])
			if existing then return {tonumber(existing), 0} end
			local seq = redis.call('INCR', KEYS[2])
			redis.call('XADD', KEYS[3], 'MAXLEN', '~', '200000', '*', 'seq', seq, 'id', ARGV[2], 'body', ARGV[1])
			redis.call('SET', KEYS[1], seq, 'EX', ARGV[3])
			return {seq, 1}
			""";
	static final String JOIN_LUA = """
			if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
			local left = redis.call('DECR', KEYS[1])
			if left < 0 then redis.call('INCR', KEYS[1]) return -1 end
			redis.call('SADD', KEYS[2], ARGV[1])
			return left
			""";

	private final StringRedisTemplate redis;
	private final DefaultRedisScript<List> send = new DefaultRedisScript<>(SEND_LUA, List.class);
	private final DefaultRedisScript<Long> join = new DefaultRedisScript<>(JOIN_LUA, Long.class);

	public RedisChatStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override public String getPresence(long userId) { return redis.opsForValue().get(Keys.presence(userId)); }
	@Override public void setPresence(long userId, String value) { redis.opsForValue().set(Keys.presence(userId), value, Duration.ofMinutes(30)); }
	@Override public long incrUnread(long userId) { Long r = redis.opsForValue().increment(Keys.unread(userId)); return r == null ? 0 : r; }

	@Override
	@SuppressWarnings("unchecked")
	public long[] sendMessage(long roomId, String clientMsgId, String body, boolean hashTag) {
		List<Long> r = redis.execute(send, List.of(Keys.roomDedupe(roomId, clientMsgId, hashTag), Keys.roomSeq(roomId, hashTag), Keys.roomStream(roomId, hashTag)),
				body, clientMsgId, "600");
		return new long[]{r.get(0), r.get(1)};
	}

	@Override
	public long join(long roomId, long userId) {
		Long r = redis.execute(join, List.of(Keys.roomSlots(roomId), Keys.roomMembers(roomId)), String.valueOf(userId));
		return r == null ? -1 : r;
	}

	@Override
	public List<String> presenceOf(List<Long> userIds) {
		return redis.opsForValue().multiGet(userIds.stream().map(Keys::presence).toList());
	}

	@Override
	public List<Long> streamSeqs(long roomId) {
		List<MapRecord<String, Object, Object>> recs = redis.opsForStream().range(Keys.roomStream(roomId, true), Range.unbounded());
		List<Long> out = new ArrayList<>(recs == null ? 0 : recs.size());
		if (recs != null) for (var r : recs) out.add(Long.parseLong(String.valueOf(r.getValue().get("seq"))));
		return out;
	}

	@Override
	@SuppressWarnings("unchecked")
	public long waitReplicas(String key, int replicas, long timeoutMs) {
		// Spring 의 generic execute("WAIT") 는 정수 응답을 못 받으므로(ByteArrayOutput) Lettuce 네이티브 API 로 보낸다.
		// Cluster 는 키를 가진 Primary 연결에서, 단일/Sentinel(MasterReplica 포함)은 현재 연결에서 실행한다.
		Object r = redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
			Object nc = c.getNativeConnection(); // Spring 은 비동기 명령 객체를 돌려준다 → StatefulConnection 으로 올라간다
			if (nc instanceof io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands<?, ?> ac) nc = ac.getStatefulConnection();
			else if (nc instanceof io.lettuce.core.api.async.RedisAsyncCommands<?, ?> ac) nc = ac.getStatefulConnection();
			if (nc instanceof io.lettuce.core.cluster.api.StatefulRedisClusterConnection<?, ?> cc) {
				var node = cc.getPartitions().getMasterBySlot(io.lettuce.core.cluster.SlotHash.getSlot(key));
				if (node == null) throw new IllegalStateException("no master for slot of " + key);
				return ((io.lettuce.core.cluster.api.StatefulRedisClusterConnection<byte[], byte[]>) cc)
						.getConnection(node.getUri().getHost(), node.getUri().getPort()).sync().waitForReplication(replicas, timeoutMs);
			}
			if (nc instanceof io.lettuce.core.api.StatefulRedisConnection<?, ?> sc)
				return ((io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>) sc).sync().waitForReplication(replicas, timeoutMs);
			throw new IllegalStateException("unsupported native connection " + nc.getClass().getName());
		});
		return r instanceof Number num ? num.longValue() : 0;
	}

	public String rawGet(String key) { return redis.opsForValue().get(key); }

	@Override
	public void flushAll() {
		redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.serverCommands().flushAll(); return null; });
	}
}

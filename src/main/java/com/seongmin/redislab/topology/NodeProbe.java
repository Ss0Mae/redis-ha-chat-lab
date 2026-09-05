package com.seongmin.redislab.topology;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 감시 전용 직접 연결(앱 연결과 분리). 300 ms 안에 답이 없으면 DOWN 으로 본다. */
public class NodeProbe implements AutoCloseable {

	private final RedisClient client;
	private final Map<String, StatefulRedisConnection<String, String>> conns = new HashMap<>();
	private final Map<String, RedisURI> uris = new HashMap<>();

	public NodeProbe() {
		client = RedisClient.create();
		client.setOptions(io.lettuce.core.ClientOptions.builder().autoReconnect(true)
				.disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
				.timeoutOptions(io.lettuce.core.TimeoutOptions.enabled(Duration.ofMillis(400)))
				.socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).build());
	}

	public void register(String name, String ip, int port) { uris.put(name, RedisURI.builder().withHost(ip).withPort(port).withTimeout(Duration.ofMillis(400)).build()); }

	public synchronized StatefulRedisConnection<String, String> conn(String name) {
		StatefulRedisConnection<String, String> c = conns.get(name);
		if (c != null && c.isOpen()) return c;
		if (c != null) c.closeAsync();
		c = client.connect(uris.get(name));
		conns.put(name, c);
		return c;
	}

	/** @return null 이면 도달 불가 */
	public Map<String, String> info(String name, String section) {
		try {
			return parseInfo(conn(name).sync().info(section));
		} catch (Exception e) {
			return null;
		}
	}

	public String call(String name, java.util.function.Function<io.lettuce.core.api.sync.RedisCommands<String, String>, String> f) {
		try { return f.apply(conn(name).sync()); } catch (Exception e) { return null; }
	}

	private final Map<String, io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection<String, String>> sentinelConns = new HashMap<>();

	/** Sentinel 전용 연결로 SENTINEL master <name>. @return null 이면 Sentinel 도달 불가 */
	public synchronized Map<String, String> sentinelMaster(String name, String masterName) {
		try {
			var c = sentinelConns.get(name);
			if (c == null || !c.isOpen()) { if (c != null) c.closeAsync(); c = client.connectSentinel(uris.get(name)); sentinelConns.put(name, c); }
			return c.sync().master(masterName);
		} catch (Exception e) {
			return null;
		}
	}

	public RedisClient client() { return client; }

	static Map<String, String> parseInfo(String s) {
		Map<String, String> m = new HashMap<>();
		for (String line : s.split("\r?\n")) {
			int i = line.indexOf(':');
			if (i > 0 && !line.startsWith("#")) m.put(line.substring(0, i), line.substring(i + 1));
		}
		return m;
	}

	@Override public void close() { conns.values().forEach(StatefulRedisConnection::closeAsync); sentinelConns.values().forEach(c -> c.closeAsync()); client.shutdown(); }
}

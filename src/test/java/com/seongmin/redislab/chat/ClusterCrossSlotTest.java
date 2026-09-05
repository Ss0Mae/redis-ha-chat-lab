package com.seongmin.redislab.chat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Cluster(Testcontainers, 3 Primary): Hash Tag 없는 멀티키 Lua 는 CROSSSLOT, Hash Tag 를 쓰면 성공. MGET 은 Lettuce 가 slot 별로 나눠 보내 성공.
 * 노드는 컨테이너 IP 를 announce 하므로 Docker 네트워크에 닿는 환경(Linux, 또는 host 네트워크)에서만 실행된다. 닿지 않으면 스킵.
 */
@Testcontainers
class ClusterCrossSlotTest {

	static final Network net = Network.newNetwork();
	static GenericContainer<?>[] nodes = new GenericContainer[3];
	static LettuceConnectionFactory factory;
	static RedisChatStore store;

	@BeforeAll
	static void up() throws Exception {
		for (int i = 0; i < 3; i++) {
			nodes[i] = new GenericContainer<>("redis:7.4").withNetwork(net).withNetworkAliases("n" + i).withExposedPorts(6379)
					.withCommand("redis-server", "--cluster-enabled", "yes", "--cluster-node-timeout", "2000", "--appendonly", "no");
			nodes[i].start();
		}
		String[] ips = new String[3];
		for (int i = 0; i < 3; i++) ips[i] = nodes[i].getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
		var r = nodes[0].execInContainer("redis-cli", "--cluster", "create", ips[0] + ":6379", ips[1] + ":6379", ips[2] + ":6379", "--cluster-yes");
		assertThat(r.getExitCode()).as(r.getStdout() + r.getStderr()).isEqualTo(0);
		await().atMost(Duration.ofSeconds(20)).alias("cluster_state:ok 가 20 초 안에 되지 않음")
				.until(() -> nodes[0].execInContainer("redis-cli", "CLUSTER", "INFO").getStdout().contains("cluster_state:ok"));
		// 호스트에서 컨테이너 IP 에 닿는지 확인(macOS 는 안 닿음 → 스킵)
		boolean reachable;
		try (var s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress(ips[0], 6379), 500); reachable = true; } catch (Exception e) { reachable = false; }
		org.junit.jupiter.api.Assumptions.assumeTrue(reachable, "컨테이너 IP(" + ips[0] + ")에 직접 닿지 않는 환경이라 Cluster 테스트를 건너뜀 (Linux 또는 compose 스택 통합 테스트로 확인)");
		factory = new LettuceConnectionFactory(new RedisClusterConfiguration(List.of(ips[0] + ":6379")));
		factory.afterPropertiesSet();
		store = new RedisChatStore(new StringRedisTemplate(factory));
	}

	@AfterAll static void down() { if (factory != null) factory.destroy(); for (var n : nodes) if (n != null) n.stop(); }

	@Test
	void crossSlotFailsWithoutHashTagAndSucceedsWithIt() {
		assertThatThrownBy(() -> store.sendMessage(42, "m1", "x", false)).isInstanceOf(RedisSystemException.class).hasMessageContaining("CROSSSLOT");
		assertThat(store.sendMessage(42, "m1", "x", true)[0]).isEqualTo(1);
		assertThat(store.presenceOf(List.of(1L, 2L, 3L, 4L, 5L))).as("MGET 은 slot 별로 나뉘어 실행").hasSize(5);
	}
}

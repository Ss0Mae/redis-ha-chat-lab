package com.seongmin.redislab.chat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** 단일 Redis(Testcontainers): 기본 명령, 멱등 Lua, 원자 정원 감소. */
@Testcontainers
class SingleRedisChatStoreTest {

	@Container
	static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);
	static LettuceConnectionFactory factory;
	static RedisChatStore store;

	@BeforeAll
	static void up() {
		factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
		factory.afterPropertiesSet();
		store = new RedisChatStore(new StringRedisTemplate(factory));
	}

	@AfterAll static void down() { factory.destroy(); }

	@Test
	void presenceAndUnread() {
		store.setPresence(1, "42");
		assertThat(store.getPresence(1)).isEqualTo("42");
		assertThat(store.incrUnread(1)).isEqualTo(1);
		assertThat(store.incrUnread(1)).isEqualTo(2);
		assertThat(store.presenceOf(java.util.List.of(1L, 2L))).containsExactly("42", null);
	}

	@Test
	void sendMessageIsIdempotentByClientMsgId() {
		long[] first = store.sendMessage(7, "msg-a", "hello", true);
		long[] again = store.sendMessage(7, "msg-a", "hello", true);
		long[] next = store.sendMessage(7, "msg-b", "world", true);
		assertThat(first[1]).as("첫 전송은 새로 생성").isEqualTo(1);
		assertThat(again[0]).as("같은 clientMsgId 는 같은 seq").isEqualTo(first[0]);
		assertThat(again[1]).as("중복 전송은 생성 아님").isEqualTo(0);
		assertThat(next[0]).isEqualTo(first[0] + 1);
		assertThat(store.streamSeqs(7)).as("스트림에는 seq 가 한 번씩만").containsExactly(first[0], next[0]);
	}

	@Test
	void joinDecrementsCapacityAtomically() {
		new StringRedisTemplate(factory).opsForValue().set(Keys.roomSlots(9), "2");
		assertThat(store.join(9, 1)).isEqualTo(1);
		assertThat(store.join(9, 2)).isEqualTo(0);
		assertThat(store.join(9, 3)).as("정원 초과").isEqualTo(-1);
		assertThat(store.join(9, 1)).as("이미 멤버").isEqualTo(-2);
	}
}

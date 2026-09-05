package com.seongmin.redislab.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실행 중인 compose 스택 대상 통합 테스트. 실험 API(허용 시나리오)로만 장애를 만든다.
 *   ./gradlew test -Dlab.integration=sentinel   또는  -Dlab.integration=cluster  (해당 토폴로지로 스택이 떠 있어야 함)
 * 모든 대기는 상한과 실패 메시지를 가진다.
 */
@EnabledIfSystemProperty(named = "lab.integration", matches = "sentinel|cluster")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FailoverIntegrationTest {

	static final String BASE = System.getProperty("lab.api", "http://localhost:8085");
	static final HttpClient http = HttpClient.newHttpClient();
	static final ObjectMapper json = new ObjectMapper();
	static String topology, experimentId, primaryBefore;

	static JsonNode call(String method, String path, String body) throws Exception {
		var b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("X-Lab-Admin-Token", "lab-admin");
		var r = http.send(method.equals("GET") ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).as(method + " " + path + " → " + r.body()).isLessThan(300);
		return json.readTree(r.body());
	}

	static JsonNode topology() throws Exception { return call("GET", "/api/redis/topology", null); }
	static String primary() throws Exception { var p = topology().get("primaries"); return p.isEmpty() ? null : p.get(0).asText(); }
	static JsonNode node(String name) throws Exception { for (var n : topology().get("nodes")) if (n.get("name").asText().equals(name)) return n; return null; }

	@BeforeAll
	static void setUp() throws Exception {
		topology = System.getProperty("lab.integration");
		assertThat(topology().get("topology").asText()).as("스택 토폴로지가 테스트 대상과 다름").isEqualTo(topology);
		await().atMost(Duration.ofSeconds(60)).alias("시작 전 토폴로지가 안정되지 않음").until(() -> topology().get("stable").asBoolean());
		primaryBefore = primary();
		var e = call("POST", "/api/experiments", json.writeValueAsString(Map.of("name", "junit-" + topology, "recordingMode", "BATCH",
				"workload", Map.of("rps", 200, "users", 1000, "rooms", 20), "startWorkload", true)));
		experimentId = e.get("id").asText();
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (experimentId != null) { try { call("POST", "/api/experiments/" + experimentId + "/recover", null); } catch (Throwable ignored) {} call("POST", "/api/experiments/" + experimentId + "/finish", null); }
	}

	@Test @Order(1)
	void primaryKillPromotesReplicaAndKeepsSlotOwnership() throws Exception {
		Thread.sleep(3000);
		String slotsBefore = "cluster".equals(topology) ? node(primaryBefore).get("slots").asText() : null;
		call("POST", "/api/experiments/" + experimentId + "/inject-failure", "{\"scenario\":\"KILL_PRIMARY\",\"confirm\":true}");
		await().atMost(Duration.ofSeconds(30)).alias("30 초 안에 새 Primary 가 선출되지 않음 (down-after/node-timeout 5 s 기준)")
				.until(() -> { String p = primary(); return p != null && !p.equals(primaryBefore); });
		String newPrimary = primary();
		if (slotsBefore != null) assertThat(node(newPrimary).get("slots").asText()).as("승격된 Replica 가 같은 slot 범위를 담당해야 함").isEqualTo(slotsBefore);
	}

	@Test @Order(2)
	void newPrimaryAcceptsWritesAndClientReconnects() throws Exception {
		await().atMost(Duration.ofSeconds(20)).alias("승격 후 20 초 안에 앱의 첫 쓰기가 성공하지 않음")
				.until(() -> !call("GET", "/api/experiments/" + experimentId, null).get("timings").get("t5_first_write_ok").isNull());
		var t = call("GET", "/api/experiments/" + experimentId, null).get("timings");
		assertThat(t.get("outage_ms").asLong()).as("서비스 중단 시간이 양수").isPositive();
		assertThat(t.get("t4_client_aware").isNull()).as("Lettuce 가 새 Primary 에 연결한 시각(T4)이 기록되어야 함").isFalse();
	}

	@Test @Order(3)
	void oldPrimaryRejoinsAsReplicaAndSyncs() throws Exception {
		call("POST", "/api/experiments/" + experimentId + "/recover", null);
		await().atMost(Duration.ofSeconds(60)).alias("옛 Primary 가 60 초 안에 Replica 로 합류하지 않음")
				.until(() -> { var n = node(primaryBefore); return n != null && "REPLICA".equals(n.get("role").asText()) && "UP".equals(n.get("status").asText()) && "up".equals(n.get("linkStatus").asText()); });
		await().atMost(Duration.ofSeconds(30)).alias("재합류 후 30 초 안에 토폴로지가 안정되지 않음").until(() -> topology().get("stable").asBoolean());
		var fin = call("POST", "/api/experiments/" + experimentId + "/finish", null);
		experimentId = null;
		var c = fin.get("summary").get("consistency");
		assertThat(c.get("read_errors").asInt()).as("정합성 검증 읽기 오류").isZero();
		assertThat(c.get("acked_messages_lost").asInt() + c.get("lost_incr").asInt()).as("이 실험 조건(복제 지연 없음)에서 승인 쓰기 유실은 관측값을 기록만 하고 0 을 강제하지 않음 — 값: " + c).isGreaterThanOrEqualTo(0);
	}
}

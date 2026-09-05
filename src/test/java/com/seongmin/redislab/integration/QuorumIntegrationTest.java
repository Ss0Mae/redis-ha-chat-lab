package com.seongmin.redislab.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Sentinel quorum 부족: Sentinel 2/3 을 멈춘 뒤 Primary 를 죽여도 40 초 동안 승격이 일어나지 않아야 한다(부정 대기도 상한 명시). */
@EnabledIfSystemProperty(named = "lab.integration", matches = "sentinel")
class QuorumIntegrationTest {

	static final String BASE = System.getProperty("lab.api", "http://localhost:8085");
	static final HttpClient http = HttpClient.newHttpClient();
	static final ObjectMapper json = new ObjectMapper();
	static String experimentId;

	static JsonNode call(String method, String path, String body) throws Exception {
		var b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("X-Lab-Admin-Token", "lab-admin");
		var r = http.send(method.equals("GET") ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).as(method + " " + path + " → " + r.body()).isLessThan(300);
		return json.readTree(r.body());
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (experimentId != null) { call("POST", "/api/experiments/" + experimentId + "/recover", null); Thread.sleep(15000); call("POST", "/api/experiments/" + experimentId + "/finish", null); }
	}

	@Test
	void noFailoverWithoutSentinelMajority() throws Exception {
		await().atMost(Duration.ofSeconds(60)).alias("시작 전 토폴로지가 안정되지 않음").until(() -> call("GET", "/api/redis/topology", null).get("stable").asBoolean());
		String primaryBefore = call("GET", "/api/redis/topology", null).get("primaries").get(0).asText();
		experimentId = call("POST", "/api/experiments", json.writeValueAsString(Map.of("name", "junit-quorum", "recordingMode", "MEMORY", "workload", Map.of("rps", 100, "users", 500, "rooms", 10), "startWorkload", true))).get("id").asText();
		call("POST", "/api/experiments/" + experimentId + "/inject-failure", "{\"scenario\":\"STOP_SENTINELS_THEN_KILL_PRIMARY\",\"confirm\":true}");
		// 40 초 동안 Primary 가 바뀌지 않아야 한다 (감지 5 s + 선출 여유를 충분히 넘는 창)
		await().during(Duration.ofSeconds(40)).atMost(Duration.ofSeconds(45)).alias("quorum 부족인데 Failover 가 일어남")
				.until(() -> { var p = call("GET", "/api/redis/topology", null).get("primaries"); return p.isEmpty() || p.get(0).asText().equals(primaryBefore); });
		var t = call("GET", "/api/experiments/" + experimentId, null).get("timings");
		assertThat(t.get("t3_promoted").isNull()).as("승격 시각이 없어야 함").isTrue();
	}
}

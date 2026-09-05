package com.seongmin.redislab.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seongmin.redislab.config.LabProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * docker-socket-proxy 를 통한 Docker Engine API. 컨테이너 이름은 호출자가 카탈로그에서만 가져온다.
 */
@Component
@Profile("local-experiment")
public class DockerApi {

	private final String base;
	private final ObjectMapper json;

	public DockerApi(LabProperties lab, ObjectMapper json) {
		this.base = lab.dockerApi();
		this.json = json;
	}

	public record State(String status, boolean running, boolean paused) {}

	/** 요청마다 새 연결. 프록시(HAProxy)가 닫은 keep-alive 연결을 재사용하다 "received no bytes" 로 실패하는 일을 막는다. */
	private static HttpClient http() { return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build(); }

	public State state(String container) {
		JsonNode n = get("/containers/" + container + "/json").path("State");
		return new State(n.path("Status").asText(), n.path("Running").asBoolean(), n.path("Paused").asBoolean());
	}

	public void stop(String container, int seconds) { post("/containers/" + container + "/stop?t=" + seconds, null); }
	public void kill(String container) { post("/containers/" + container + "/kill", null); }
	public void start(String container) { post("/containers/" + container + "/start", null); }
	public void pause(String container) { post("/containers/" + container + "/pause", null); }
	public void unpause(String container) { post("/containers/" + container + "/unpause", null); }
	public void restart(String container) { post("/containers/" + container + "/restart?t=1", null); }

	/** 사이드카의 고정 스크립트 실행. @return 종료 코드 */
	public int exec(String container, List<String> cmd) {
		JsonNode created = post("/containers/" + container + "/exec", Map.of("AttachStdout", true, "AttachStderr", true, "Cmd", cmd));
		String id = created.path("Id").asText();
		// Detach=false 는 연결을 raw 스트림으로 hijack 해 Java HttpClient 가 처리하지 못한다. 분리 실행 후 종료 코드만 폴링한다.
		post("/exec/" + id + "/start", Map.of("Detach", true, "Tty", false));
		for (int i = 0; i < 50; i++) {
			JsonNode st = get("/exec/" + id + "/json");
			if (!st.path("Running").asBoolean()) return st.path("ExitCode").asInt(-1);
			try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
		}
		return -1;
	}

	/** 컨테이너 로그(타임스탬프 포함). TTY 가 없으면 8바이트 프레임 헤더로 다중화돼 있어 벗겨 낸다. @return "<rfc3339> <line>" 목록 */
	public List<String> logs(String container, long sinceEpochSeconds) {
		try {
			HttpResponse<byte[]> r = http().send(HttpRequest.newBuilder(URI.create(base + "/containers/" + container + "/logs?stdout=true&stderr=true&timestamps=true&since=" + sinceEpochSeconds))
					.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (r.statusCode() >= 300) return List.of();
			byte[] b = r.body();
			StringBuilder sb = new StringBuilder();
			int i = 0;
			while (i + 8 <= b.length) {
				int size = ((b[i + 4] & 0xff) << 24) | ((b[i + 5] & 0xff) << 16) | ((b[i + 6] & 0xff) << 8) | (b[i + 7] & 0xff);
				if (size < 0 || i + 8 + size > b.length) { sb.append(new String(b, i, b.length - i, java.nio.charset.StandardCharsets.UTF_8)); break; }
				sb.append(new String(b, i + 8, size, java.nio.charset.StandardCharsets.UTF_8));
				i += 8 + size;
			}
			return List.of(sb.toString().split("\n"));
		} catch (IOException | InterruptedException e) {
			return List.of();
		}
	}

	private JsonNode get(String path) {
		try {
			HttpResponse<String> r = http().send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() >= 300) throw new IllegalStateException("docker GET " + path + " -> " + r.statusCode() + " " + r.body());
			return json.readTree(r.body());
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException("docker api: " + e.getMessage(), e);
		}
	}

	private JsonNode post(String path, Object body) {
		try {
			String b = body == null ? "" : json.writeValueAsString(body);
			HttpResponse<String> r = http().send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(b)).build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() >= 300 && r.statusCode() != 304) throw new IllegalStateException("docker POST " + path + " -> " + r.statusCode() + " " + r.body());
			return r.body().isBlank() || r.statusCode() == 304 ? json.createObjectNode() : json.readTree(r.body().startsWith("{") ? r.body() : "{}");
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException("docker api: " + e.getMessage(), e);
		}
	}
}

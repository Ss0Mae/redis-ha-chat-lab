package com.seongmin.redislab.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seongmin.redislab.config.LabProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 앱 ↔ Sentinel 프록시(sentinel-1..3)에만 toxic 을 건다. */
@Component
@Profile("local-experiment")
public class ToxiproxyClient {

	static final List<String> PROXIES = List.of("sentinel-1", "sentinel-2", "sentinel-3");
	private final String base;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private final ObjectMapper json;

	public ToxiproxyClient(LabProperties lab, ObjectMapper json) { this.base = lab.toxiproxyApi(); this.json = json; }

	public void latency(int ms) { for (String p : PROXIES) add(p, "lab-latency", "latency", Map.of("latency", ms, "jitter", 0)); }
	public void timeout() { for (String p : PROXIES) add(p, "lab-timeout", "timeout", Map.of("timeout", 0)); }
	public void removeAll() { for (String p : PROXIES) for (String t : List.of("lab-latency", "lab-timeout")) delete(p, t); }

	private void add(String proxy, String name, String type, Map<String, Object> attrs) {
		try {
			String body = json.writeValueAsString(Map.of("name", name, "type", type, "stream", "downstream", "toxicity", 1.0, "attributes", attrs));
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/proxies/" + proxy + "/toxics")).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() >= 300 && r.statusCode() != 409) throw new IllegalStateException("toxiproxy " + r.statusCode() + " " + r.body());
		} catch (java.io.IOException | InterruptedException e) { throw new IllegalStateException("toxiproxy: " + e.getMessage(), e); }
	}

	private void delete(String proxy, String name) {
		try { http.send(HttpRequest.newBuilder(URI.create(base + "/proxies/" + proxy + "/toxics/" + name)).DELETE().build(), HttpResponse.BodyHandlers.discarding()); }
		catch (java.io.IOException | InterruptedException ignored) {}
	}
}

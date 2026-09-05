package com.seongmin.redislab.experiment;

import com.seongmin.redislab.fault.FaultRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/experiments")
@Profile("local-experiment")
public class ExperimentController {

	private final ExperimentService service;

	public ExperimentController(ExperimentService service) { this.service = service; }

	@PostMapping public Map<String, Object> create(@RequestBody ExperimentService.CreateRequest req) { return service.toMap(service.create(req)); }
	@PostMapping("/{id}/inject-failure") public Map<String, Object> inject(@PathVariable String id, @Valid @RequestBody FaultRequest req) { return service.toMap(service.injectFailure(id, req)); }
	@PostMapping("/{id}/recover") public Map<String, Object> recover(@PathVariable String id) { return service.toMap(service.recover(id)); }
	@PostMapping("/{id}/phase") public Map<String, Object> phase(@PathVariable String id, @RequestBody Map<String, String> body) { return service.toMap(service.setPhase(id, body.getOrDefault("phase", ""))); }
	@PostMapping("/{id}/finish") public Map<String, Object> finish(@PathVariable String id) { return service.toMap(service.finish(id)); }
	@GetMapping public List<Map<String, Object>> list() { return service.list(); }
	@GetMapping("/active") public Map<String, Object> active() { return service.active().map(service::toMap).orElse(Map.of()); }
	@GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id) { return service.view(id); }
	@GetMapping("/{id}/samples") public List<Map<String, Object>> samples(@PathVariable String id) { return service.samples(id); }

	@GetMapping(value = "/{id}/export", produces = "text/csv")
	public String exportCsv(@PathVariable String id, @RequestParam(defaultValue = "csv") String format) {
		StringBuilder b = new StringBuilder("at,phase,ok,fail,p50_us,p95_us,p99_us,max_us,by_error\n");
		for (var r : service.samples(id)) b.append(r.get("at")).append(',').append(r.get("phase")).append(',').append(r.get("ok")).append(',').append(r.get("fail")).append(',')
				.append(r.get("p50_us")).append(',').append(r.get("p95_us")).append(',').append(r.get("p99_us")).append(',').append(r.get("max_us")).append(",\"").append(String.valueOf(r.get("by_error")).replace("\"", "'")).append("\"\n");
		return b.toString();
	}
}

package com.seongmin.redislab.workload;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/workloads")
public class WorkloadController {

	private final WorkloadRunner runner;

	public WorkloadController(WorkloadRunner runner) { this.runner = runner; }

	@PostMapping("/start") public Map<String, Object> start(@Valid @RequestBody WorkloadConfig cfg) { runner.start(cfg); return runner.status(); }
	@PostMapping("/stop") public Map<String, Object> stop() { runner.stop(); return runner.status(); }
	@GetMapping("/status") public Map<String, Object> status() { return runner.status(); }
}

package com.seongmin.redislab.web;

import com.seongmin.redislab.chat.Op;
import com.seongmin.redislab.workload.OpExecutor;
import com.seongmin.redislab.workload.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** k6 가 호출하는 HTTP 워크로드. 앱 내부 워크로드와 같은 OpExecutor 를 지나므로 기록·지표가 같다. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final OpExecutor executor;

	public ChatController(OpExecutor executor) { this.executor = executor; }

	@GetMapping("/users/{id}/presence") public ResponseEntity<Map<String, Object>> get(@PathVariable long id) { return respond(executor.execute(Op.GET, id, true, 5)); }
	@PutMapping("/users/{id}/presence") public ResponseEntity<Map<String, Object>> set(@PathVariable long id) { return respond(executor.execute(Op.SET, id, true, 5)); }
	@PostMapping("/users/{id}/unread") public ResponseEntity<Map<String, Object>> incr(@PathVariable long id) { return respond(executor.execute(Op.INCR, id, true, 5)); }
	@PostMapping("/rooms/{id}/messages") public ResponseEntity<Map<String, Object>> send(@PathVariable long id, @RequestParam(defaultValue = "true") boolean hashTag) { return respond(executor.execute(Op.SEND, id, hashTag, 5)); }
	@GetMapping("/rooms/{id}/presence") public ResponseEntity<Map<String, Object>> mget(@PathVariable long id, @RequestParam(defaultValue = "5") int size) { return respond(executor.execute(Op.MGET, id, true, size)); }
	@PostMapping("/rooms/{id}/join/{userId}") public ResponseEntity<Map<String, Object>> join(@PathVariable long id, @PathVariable long userId) { return respond(executor.execute(Op.JOIN, id, true, 5)); }

	private ResponseEntity<Map<String, Object>> respond(Result r) {
		Map<String, Object> body = Map.of("outcome", r.outcome().name(), "seq", r.request().seq(), "result", String.valueOf(r.resultText()), "latencyUs", r.latencyNanos() / 1000, "retried", r.retried());
		return ResponseEntity.status(r.ok() ? 200 : 503).body(body);
	}
}

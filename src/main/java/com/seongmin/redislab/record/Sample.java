package com.seongmin.redislab.record;

import java.time.Instant;
import java.util.Map;

/** 1초 집계. SSE 로 대시보드에, MySQL metric_sample 에 저장된다. */
public record Sample(Instant at, String phase, int ok, int fail, Map<String, Integer> byOp, Map<String, Integer> byError,
		int p50Us, int p95Us, int p99Us, int maxUs, int okWrites, int failWrites, Map<String, Integer> byShard) {}

package com.seongmin.redislab.workload;

import com.seongmin.redislab.chat.Outcome;

import java.time.Instant;

public record Result(Request request, Outcome outcome, long latencyNanos, Instant ackedAt, String resultText, boolean retried) {
	public boolean ok() { return outcome == Outcome.OK; }
}

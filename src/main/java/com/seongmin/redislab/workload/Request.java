package com.seongmin.redislab.workload;

import com.seongmin.redislab.chat.Op;

import java.time.Instant;

public record Request(String experimentId, String requestId, long seq, Op op, long id, String key, String value, int shard, Instant requestedAt) {}

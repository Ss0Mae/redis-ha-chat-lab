package com.seongmin.redislab.chat;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.cluster.PartitionSelectorException;
import io.lettuce.core.cluster.UnknownPartitionException;

import java.util.Locale;

/** 응답 결과 분류. 실패는 원인 예외를 따라가며 Redis 오류 접두어로 나눈다. */
public enum Outcome {
	OK, TIMEOUT, CONNECTION, MOVED, ASK, CROSSSLOT, CLUSTERDOWN, READONLY, NOREPLICAS, WAIT_TIMEOUT, OTHER;

	public static Outcome classify(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof RedisCommandTimeoutException || c instanceof java.util.concurrent.TimeoutException) return TIMEOUT;
			if (c instanceof RedisConnectionException || c instanceof java.net.ConnectException || c instanceof java.io.IOException
					|| c instanceof PartitionSelectorException || c instanceof UnknownPartitionException) return CONNECTION;
			if (c instanceof RedisCommandExecutionException) {
				String m = c.getMessage() == null ? "" : c.getMessage().toUpperCase(Locale.ROOT);
				if (m.startsWith("MOVED")) return MOVED;
				if (m.startsWith("ASK")) return ASK;
				if (m.startsWith("CROSSSLOT")) return CROSSSLOT;
				if (m.startsWith("CLUSTERDOWN")) return CLUSTERDOWN;
				if (m.startsWith("READONLY")) return READONLY;
				if (m.startsWith("NOREPLICAS")) return NOREPLICAS;
				return OTHER;
			}
			String m = c.getMessage() == null ? "" : c.getMessage();
			if (m.contains("Connection reset") || m.contains("Connection refused") || m.contains("not connected") || m.contains("Connection closed")) return CONNECTION;
		}
		return OTHER;
	}
}

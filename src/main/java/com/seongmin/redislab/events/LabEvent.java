package com.seongmin.redislab.events;

import java.time.Instant;

/** 대시보드(SSE)와 실험 타임라인이 함께 쓰는 이벤트. kind: topology | sample | event | fault | workload */
public record LabEvent(String kind, Instant at, String source, String type, Object payload) {
	public static LabEvent of(String kind, String source, String type, Object payload) {
		return new LabEvent(kind, Instant.now(), source, type, payload);
	}
}

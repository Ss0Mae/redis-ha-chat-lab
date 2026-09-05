package com.seongmin.redislab.workload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 초당 요청 수와 명령 비율(가중치). 기본 50/20/10/15/5, 사용자 10,000, 방 100. */
public record WorkloadConfig(@Min(1) @Max(5000) int rps, Mix mix, @Min(1) int users, @Min(1) int rooms, Boolean hashTag, Integer mgetSize) {
	public record Mix(int get, int set, int incr, int send, int mget) {
		public int total() { return get + set + incr + send + mget; }
	}
	public WorkloadConfig {
		if (mix == null || mix.total() <= 0) mix = new Mix(50, 20, 10, 15, 5);
		if (users <= 0) users = 10_000;
		if (rooms <= 0) rooms = 100;
		if (hashTag == null) hashTag = true;
		if (mgetSize == null || mgetSize <= 0) mgetSize = 5;
	}
}

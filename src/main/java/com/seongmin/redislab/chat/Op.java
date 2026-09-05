package com.seongmin.redislab.chat;

/** 워크로드 명령. write 는 정합성 검증 대상, idempotent 는 재시도 안전 여부. */
public enum Op {
	GET(false, true), SET(true, true), INCR(true, false), SEND(true, true), MGET(false, true), JOIN(true, false);

	public final boolean write, idempotent;

	Op(boolean write, boolean idempotent) { this.write = write; this.idempotent = idempotent; }
}

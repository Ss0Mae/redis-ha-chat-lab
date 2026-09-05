package com.seongmin.redislab.chat;

import java.util.List;

/** Sentinel/Cluster 공통 업무 인터페이스. 구현은 하나(RedisChatStore)이고 연결 방식만 프로필로 바뀐다. */
public interface ChatStore {
	String getPresence(long userId);
	void setPresence(long userId, String value);
	long incrUnread(long userId);
	/** @return [seq, created(1|0)] — 같은 clientMsgId 를 다시 보내면 같은 seq 를 돌려준다(멱등). */
	long[] sendMessage(long roomId, String clientMsgId, String body, boolean hashTag);
	/** 정원 있는 방 입장 = 원자적 재고 감소. @return 남은 정원, -1 = 정원 초과, -2 = 이미 멤버 */
	long join(long roomId, long userId);
	List<String> presenceOf(List<Long> userIds);
	List<Long> streamSeqs(long roomId);
	/** 키를 가진 Primary 에서 WAIT numreplicas timeout. @return 확인된 Replica 수 */
	long waitReplicas(String key, int replicas, long timeoutMs);
	void flushAll();
}

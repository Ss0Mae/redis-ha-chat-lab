package com.seongmin.redislab.chat;

/** 채팅 키 설계. 사용자 키는 slot 이 흩어지고(분산 키), 방 키는 Hash Tag 로 한 slot 에 모인다. */
public final class Keys {
	private Keys() {}
	public static String presence(long userId) { return "user:" + userId + ":presence"; }
	public static String unread(long userId) { return "user:" + userId + ":unread"; }
	public static String roomSeq(long roomId, boolean hashTag) { return room(roomId, hashTag) + ":seq"; }
	public static String roomStream(long roomId, boolean hashTag) { return room(roomId, hashTag) + ":stream"; }
	public static String roomDedupe(long roomId, String clientMsgId, boolean hashTag) { return room(roomId, hashTag) + ":dedupe:" + clientMsgId; }
	public static String roomSlots(long roomId) { return room(roomId, true) + ":slots"; }
	public static String roomMembers(long roomId) { return room(roomId, true) + ":members"; }
	private static String room(long roomId, boolean hashTag) { return hashTag ? "room:{" + roomId + "}" : "room:" + roomId; }
}

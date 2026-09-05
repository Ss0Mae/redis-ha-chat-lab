# EXP-CROSSSLOT. Hash Slot 과 멀티키 제약 — Hash Tag 적용 전후

```text
실험 이름: CROSSSLOT 재현 (Cluster 스택, 앱 API 직접 호출 + redis-cli)
검증하려는 가설: 같은 방의 키 세 개(seq, stream, dedupe)를 한 Lua 스크립트로 다루려면 같은 slot 에 있어야 한다. Hash Tag 없이는 CROSSSLOT,
                있으면 성공. MGET 은 Lettuce 가 slot 별로 나눠 보내므로 여러 slot 이어도 성공한다. Sentinel 은 제약이 없다.
변경한 항목: 키 이름만 — room:42:* (Hash Tag 없음) ↔ room:{42}:* (Hash Tag).
통제한 조건: 같은 Lua 스크립트(send.lua), 같은 Cluster(3P+3R), 같은 앱.
```

## 측정 결과

`CLUSTER KEYSLOT` (c-1 에서 실행):

| 키 | slot | 비고 |
|---|---:|---|
| `room:42:seq` | 10,714 | c-2 담당 |
| `room:42:stream` | 8,935 | c-2 담당 |
| `room:42:dedupe:m1` | 1,987 | c-1 담당 → 세 키가 두 노드에 흩어짐 |
| `room:{42}:seq` | 8,000 | 중괄호 안 "42" 만 해시 |
| `room:{42}:stream` | 8,000 | 같은 slot |
| `room:{42}:dedupe:m1` | 8,000 | 같은 slot |
| `user:1:presence` / `user:2:presence` / `user:3:presence` | 7,339 / 11,620 / 545 | 분산 키(세 Primary 에 고루) |

앱 API 호출 결과 (`POST /api/chat/rooms/42/messages`):

```text
hashTag=false → {"outcome":"CROSSSLOT","result":"RedisSystemException: Error in execution","latencyUs":37065}
hashTag=true  → {"outcome":"OK","result":"1","latencyUs":2188}
GET /api/chat/rooms/1/presence?size=8 (MGET, 8개 slot) → {"outcome":"OK","result":"8","latencyUs":3458}
```

redis-cli 로 같은 것을 재현:

```text
EVAL "return redis.call('GET', KEYS[1])" 2 room:42:seq room:42:stream
→ (error) CROSSSLOT Keys in request don't hash to the same slot
MGET user:1:presence user:2:presence (redis-cli, -c 없이)
→ (error) CROSSSLOT Keys in request don't hash to the same slot
```

같은 코드를 Sentinel 스택에서 호출하면 `hashTag=false` 도 성공한다(단일 Primary 에 모든 키가 있으므로 slot 개념이 없다). 단위 테스트: `SingleRedisChatStoreTest.sendMessageIsIdempotentByClientMsgId` (단일 Redis, Hash Tag 무관), `ClusterCrossSlotTest` (컨테이너 IP 에 닿는 환경에서 CROSSSLOT → Hash Tag 성공을 검증, macOS 에서는 스킵되고 compose 스택으로 확인).

## 해석

- 서버가 거부하는 것과 클라이언트가 대신 해 주는 것을 구분해야 한다. `MGET` 이 여러 slot 에 걸쳐 성공한 것은 Redis 가 허용해서가 아니라 **Lettuce 가 키를 slot 별로 나눠 보내고 합쳤기** 때문이다(redis-cli 로는 실패). `MULTI/EXEC`·Lua 처럼 원자성이 필요한 명령은 클라이언트가 나눌 수 없으므로 서버 제약이 그대로 드러난다.
- Hash Tag 는 "관련 키를 한 slot 에" 라는 뜻이지 "한 노드에 고루" 가 아니다. 인기 방이 몇 개 있으면 그 slot 을 가진 Primary 로 부하가 쏠린다(핫 slot). 방 100개를 `{roomId}` 로 태그한 이 실험에서는 세 Primary 의 요청 비율이 각각 33% 안팎이었다(대시보드 slot 별 요청량).
- 사용자 소유 키(`user:{7}:rooms`)와 방 키를 한 스크립트로 묶는 "입장" 기능은 Hash Tag 로 해결되지 않는다. 방 쪽(정원·멤버)만 Lua 로 원자 처리하고 사용자 쪽은 멱등 쓰기로 나눴다(`RedisChatStore.join`). Cluster 를 고르는 순간 **원자성의 단위가 slot** 이 된다.

## 결론

Cluster 로 가면 키 설계가 곧 트랜잭션 설계다. Hash Tag 로 같이 바뀌어야 하는 키를 한 slot 에 두고, 그럴 수 없는 관계는 애플리케이션 단계에서 멱등성으로 보상해야 한다. Sentinel 은 이 제약이 없어서 기존 코드를 그대로 쓸 수 있다는 것이 운영 복잡도 비교의 한 축이다.

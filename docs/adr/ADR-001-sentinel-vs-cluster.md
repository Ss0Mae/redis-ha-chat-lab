# ADR-001 채팅 서비스의 Redis 고가용성 구성 선택 기준 — Sentinel 인가 Cluster 인가

**상태**: 채택 (2026-09-06, 실측 완료 후 작성). 수치는 `docs/results.md`(300 RPS, 5회 중앙값).

## 문제

채팅 서비스의 presence·unread·메시지 스트림을 Redis 에 둔다. Primary 한 대는 SPOF 이므로 자동 Failover 가 필요하다. 후보는 Sentinel(Primary 1 + Replica 2 + Sentinel 3)과 Cluster(Primary 3 + Replica 3). 문서상의 차이가 아니라 같은 장애에서 실제로 무엇이 얼마나 달라지는지를 기준으로 고르고 싶었다.

## 실측으로 확인된 차이

| 축 | Sentinel | Cluster | 근거 |
|---|---|---|---|
| Primary SIGKILL 서비스 중단 | **6.6 s** | 12.0 s | EXP-AB |
| 장애 범위 | 전체(성공 0건 6 s) | 키의 1/3(성공 0건 0 s) | EXP-AB |
| hang(pause) 장애 | 40.2 s, 오류 98 %, 유실 1,310 — 기본 클라이언트가 승격을 모름 | 10.5 s | EXP-AB / OPT |
| split-brain(Primary↔감시자 단절) | 유실 4,084(75 %), 오류 0 % — 조용한 유실 | 과반 못 보는 Primary 가 스스로 거부 → 유실 151(2.8 %) | EXP-C |
| 옛 Primary 재합류 | 10 s | 0.5 s | EXP-G |
| 클라이언트 설정 의존 | `readFrom=UPSTREAM` 없이는 hang·파티션에 무방비 | 갱신(adaptive+periodic) 없이는 5회 중 3회 복구 실패, `REJECT_COMMANDS` 없이는 유실·중복 | EXP-OPT |
| 멀티키·Lua | 제약 없음 | Hash Tag 필수, 아니면 CROSSSLOT | EXP-CROSSSLOT |
| slot 공백(두 노드 동시 장애) | 해당 없음(전면 중단) | `require-full-coverage` 가 전면(88.6 %) / 부분(35.5 %) 을 가름 | EXP-H |
| 안전성 설정 | `min-replicas-to-write` 로 split-brain 4,084 → 73 | Replica 1대 구성에서 같은 설정은 승격 후 40 s 쓰기 거부 | EXP-SETTINGS |
| 정상 시 p95(앱 내부, 300 RPS) | 0.58 ms | 0.52 ms | results.md 2절 |

## 결정

**이 채팅 서비스의 현재 규모(Primary 한 대 메모리로 충분, 방 단위 Lua 트랜잭션 사용)에서는 Sentinel 을 택하되 다음을 필수 조건으로 한다.**

1. Lettuce `readFrom=UPSTREAM`(MasterReplica 연결) — `+switch-master` 를 구독해 hang·파티션에서 40 s → 12 s. 단순 종료가 6.6 → 11.1 s 로 느려지는 비용을 받아들인다(hang·파티션이 종료보다 드물지만 결과가 훨씬 나쁘기 때문).
2. `min-replicas-to-write 1`, `min-replicas-max-lag 1` — 조용한 유실(4,084)을 시끄러운 실패(73 + NOREPLICAS)로 바꾼다. 가용성 비용은 재시도로 흡수한다.
3. `down-after-milliseconds` 는 운영 네트워크에서 오탐이 없는 최솟값으로. 이 환경에서는 1 s 에서도 오탐 0 이었고 중단이 2.2 s 였다.
4. 재시도는 멱등 연산(GET/SET/멱등 Lua SEND)에만. INCR 은 재시도하지 않는다.
5. 설정 파일은 쓰기 가능한 볼륨에(ADR-006). 아니면 재시작이 두 번째 장애가 된다.
6. 유실이 치명적인 쓰기(메시지 전송)에만 `WAIT 1` — 복제 지연 유실 29 → 0. 지연 상태에서 p95 가 0.7 → 950 ms 로 뛰고 WAIT 실패가 "반영됐지만 승인 안 된 쓰기" 를 남기므로 멱등 Lua(SEND)에만 붙인다.

**Cluster 로 바꾸는 조건**: 데이터가 Primary 한 대 메모리를 넘거나, 쓰기가 한 노드의 처리량을 넘거나, "장애가 사용자 1/3 에만 미치는 것" 이 업무상 중요해질 때. 그때는 다음을 함께 한다.

1. Lettuce `ClusterTopologyRefreshOptions`: adaptive 트리거 + periodic 5 s (둘 중 하나만은 실패), `adaptiveRefreshTriggersTimeout` 을 node-timeout 보다 짧게.
2. `disconnectedBehavior=REJECT_COMMANDS` — 유실 28·중복 27 → 0/0.
3. 키 설계에 Hash Tag(`room:{id}:*`) 를 먼저 반영하고, 그럴 수 없는 관계는 애플리케이션 멱등성으로 보상.
4. `cluster-require-full-coverage` 를 업무에 맞게 결정(채팅: 일부 방 불가 vs 전체 중단).
5. Primary 당 Replica 2대 이상이어야 `min-replicas-to-write` 나 `WAIT 1` 을 쓸 수 있다(1대면 승격 직후 40 s 쓰기 거부).

## 기각한 대안

- **Sentinel + `TCP_USER_TIMEOUT` 으로 hang 대응** — 멈춘 프로세스의 커널이 계속 ACK 하므로 발동하지 않았다(40.2 s 그대로).
- **짧은 command timeout(200 ms)** — 중단 시간은 서버 측 감지·승격이 정하므로 줄지 않고 오류율(13.6 → 18.8 %)과 유실(Cluster 2배)만 늘었다.
- **AOF 로 승격 유실 방어** — 승격 유실은 복제 창의 문제라 AOF 와 무관(29건 그대로, p95 2.4배). AOF 는 재기동 시 데이터 보존용으로만 켠다.
- **`master-reboot-down-after-period`** — 빈 상태로 재기동한 Primary 에 Replica 가 먼저 resync 해 버려 유실을 막지 못했다(3,486 > 2,790).

## 결과·한계

이 결정은 한 장비의 Docker 안에서 잰 상대 비교에 근거한다. 절대 처리량·지연과 네트워크 오탐 특성은 운영 환경에서 다시 재야 한다. 동일 부하 비교(k6 100~2,000 RPS, EXP-PERF)에서 정상 구간 성능은 두 구성이 같았고, 장애 구간의 병목은 어느 쪽이든 앱의 요청 스레드 풀이었다 — Cluster 의 shard 격리를 앱까지 이어 가려면 shard 별 bulkhead 가 필요하다는 조건을 Cluster 전환 조건에 더한다.

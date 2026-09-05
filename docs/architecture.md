# 아키텍처와 시퀀스

## Sentinel 구성

```mermaid
flowchart LR
  subgraph app[lab-app 172.28.0.100]
    L[Lettuce]
  end
  subgraph sent[Sentinel x3, quorum 2]
    S1[s-1 .1.21] --- S2[s-2 .1.22] --- S3[s-3 .1.23]
  end
  P[(r-a-1 Primary .1.11)]
  R2[(r-a-2 Replica .1.12)]
  R3[(r-a-3 Replica .1.13)]
  P -- 복제 --> R2
  P -- 복제 --> R3
  S1 -. 감시 .-> P
  S2 -. 감시 .-> P
  S3 -. 감시 .-> P
  L -- 1. get-master-addr-by-name --> S1
  L -- 2. 명령 (직접 연결) --> P
  L -. +switch-master 구독 .-> S1
```

Sentinel 은 프록시가 아니다. 앱은 Sentinel 에서 주소를 얻어 Redis 에 직접 붙고, `+switch-master` 를 구독해 승격을 알아챈다.

## Cluster 구성과 slot 분포

```mermaid
flowchart TB
  subgraph shard0[slot 0–5460]
    C1[(c-1 Primary .2.11)] -- 복제 --> C5[(c-5 Replica .2.15)]
  end
  subgraph shard1[slot 5461–10922]
    C2[(c-2 Primary .2.12)] -- 복제 --> C6[(c-6 Replica .2.16)]
  end
  subgraph shard2[slot 10923–16383]
    C3[(c-3 Primary .2.13)] -- 복제 --> C4[(c-4 Replica .2.14)]
  end
  L[Lettuce: slot 맵 보유] --> C1 & C2 & C3
  C1 <-. cluster bus 16379 .-> C2 <-. bus .-> C3
```

키 `room:{42}:seq` 처럼 중괄호 안(Hash Tag)만 CRC16 에 넣어 같은 방의 키를 한 slot 에 모은다. 사용자 키 `user:7:presence` 는 전체 키로 해시해 slot 이 흩어진다.

## 정상 요청 시퀀스

```mermaid
sequenceDiagram
  participant W as WorkloadRunner / k6
  participant O as OpExecutor
  participant L as Lettuce
  participant R as Redis Primary
  participant Rec as RecordingService
  W->>O: execute(SEND, room 42)
  O->>O: seq 부여, requestedAt
  O->>L: EVALSHA send.lua {room:{42}:dedupe, seq, stream}
  L->>R: (Sentinel: 알려진 Primary / Cluster: slot 담당 노드)
  R-->>L: [seq, created]
  L-->>O: 결과
  O->>Rec: Result(OK, latency, ackedAt) → Ledger(ACK 장부), 1초 집계, (BATCH) 큐
```

## Primary 장애와 Replica 승격 (Sentinel)

```mermaid
sequenceDiagram
  participant App as lab-app (Lettuce)
  participant S as Sentinel x3
  participant P as Primary r-a-1
  participant R as Replica r-a-3
  Note over P: T0 SIGKILL
  App--xP: 명령 실패(연결 오류/타임아웃)
  S->>P: PING (down-after 5 s 동안 무응답)
  S->>S: +sdown → quorum 2 → +odown (T1)
  S->>S: 리더 선출(+elected-leader), +try-failover (T2)
  S->>R: REPLICAOF NO ONE
  R-->>S: role:master (T3, +promoted-slave)
  S-->>App: +switch-master (pubsub)
  App->>R: 재연결 (T4), 첫 쓰기 성공 (T5)
  S->>R2: REPLICAOF r-a-3 (다른 Replica 재구성) → +failover-end (T6)
```

## Primary 장애와 Replica 승격 (Cluster)

```mermaid
sequenceDiagram
  participant App as lab-app (Lettuce)
  participant M as 다른 Primary (c-2, c-3)
  participant P as Primary c-1
  participant R as Replica c-5
  Note over P: T0 SIGKILL
  M->>P: PING (node-timeout 5 s) → PFAIL
  M->>M: gossip 으로 과반 PFAIL → FAIL (T1)
  R->>R: 선거 지연 500 ms + rank·1 s (T2)
  R->>M: FAILOVER_AUTH_REQUEST (epoch+1)
  M-->>R: FAILOVER_AUTH_ACK (과반)
  R->>R: slot 0–5460 인수, role master (T3)
  App->>App: 토폴로지 갱신(adaptive/periodic) → ClusterTopologyChangedEvent (T4)
  App->>R: slot 0–5460 첫 쓰기 성공 (T5). 다른 slot 은 장애 중에도 정상
```

## 옛 Primary 재합류

```mermaid
sequenceDiagram
  participant Old as 옛 Primary (재시작)
  participant S as Sentinel / Cluster gossip
  participant New as 새 Primary
  Old->>Old: 기동(데이터 없음 또는 AOF)
  S->>Old: Sentinel: REPLICAOF new / Cluster: 더 높은 configEpoch 발견 → "Reconfiguring myself as a replica"
  Old->>New: PSYNC replid offset
  alt 옛 offset ≤ 분기점
    New-->>Old: +CONTINUE (partial resync)
  else 분기된 쓰기 있음 / 재시작으로 replid 없음
    New-->>Old: +FULLRESYNC (RDB 전송, 분기 쓰기 폐기)
  end
  Old-->>S: master_link_status:up → STABLE (재합류 완료)
```

## 장애 주입 경로

```mermaid
flowchart LR
  UI[React] -- POST inject-failure {scenario, target, confirm} --> API[ExperimentService]
  API -- enum 검사·토큰·동시 1개·타이머 --> FE[FaultExecutor]
  FE -- stop/kill/pause/start --> SP[docker-socket-proxy] --> D[(Docker API)]
  FE -- exec drop.sh/netem.sh/clear.sh --> SC[fault-agent 사이드카 (노드 netns 공유)]
  FE -- toxics --> TP[Toxiproxy (앱↔Sentinel 만)]
```

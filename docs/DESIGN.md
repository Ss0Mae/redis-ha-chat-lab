# redis-ha-chat-lab 설계서 — Redis Sentinel vs Redis Cluster 고가용성·장애 복구 비교

> 상태: **설계 문서(구현 완료 후 일부 갱신).** 이 문서의 시간·건수·비율은 설계 시점의 "예상"이며 실측값이 아니다. 실측값은 `docs/results.md`(자동 생성), `docs/experiments/`, `docs/timelines/` 에만 기록한다. 설계와 달라진 점: 노드·Sentinel 설정을 `/data` 에 영속(ADR-006), 앱 컨테이너 환경변수로 Lettuce 변형 주입, Cluster T1~T3 는 Redis 로그로 보정, 서비스 중단은 "첫 쓰기 실패 → 첫 성공" 기준.
> 철자: Redis **Sentinel** (Centinel 아님).

가상의 업무 도메인은 **채팅 서비스**다. 채팅방(room)에 메시지를 보내고, 사용자의 접속 상태(presence)를 갱신하고, 안 읽은 메시지 수(unread)를 세는 기능을 Redis 위에 얹는다. 채팅은 "쓰기가 끊이지 않고, 메시지 순번이 하나라도 빠지거나 중복되면 바로 티가 나는" 업무라서 Failover 중 유실·중복을 관찰하기에 알맞다.

---

## 1. 프로젝트 정의와 실험 목표

**한 문장 정의.** 같은 채팅 워크로드를 Redis Sentinel(Primary 1 + Replica 2 + Sentinel 3)과 Redis Cluster(Primary 3 + Replica 3)에 흘리면서 Primary 를 죽이고, 감지→승격→클라이언트 재연결→정상화까지를 시각(T0~T6)과 건수(실패·유실·중복)로 잰다.

**답해야 할 10개 질문과 가설 (H = 예상, 실측으로 확인·반박)**

| # | 질문 | 가설(예상) | 확인 방법 |
|---|---|---|---|
| 1 | Sentinel 과 Cluster 는 각각 어떤 문제를 푸는가 | Sentinel = 단일 Primary 의 **자동 Failover**(가용성). Cluster = 자동 Failover **+ 데이터 분산**(용량·쓰기 확장). Sentinel 은 프록시가 아니라 감시자·중재자 | 2절 비교표, 실험으로 검증 |
| 2 | Sentinel Failover 는 실제 몇 초 | `down-after-milliseconds`(기본 30 s, 실험 5 s) + 선출·승격 ≈ **down-after + 1~2 s** | T3−T0, Sentinel pubsub 이벤트 시각 |
| 3 | Cluster Primary 종료 시 담당 Replica 가 승격되는가 | `cluster-node-timeout`(실험 5 s) 후 FAIL → Replica 선거(500 ms + 랜덤 0~500 ms + rank×1 s) → 승격. 같은 slot 범위를 그대로 담당 | `CLUSTER NODES` 폴링, 노드 로그 "Failover election won" |
| 4 | Cluster 장애 중 전부 실패하는가, 해당 slot 만인가 | 키가 고르게 분산되면 **약 1/3 의 요청만** 실패. 단 `cluster-require-full-coverage yes` 이고 slot 이 비면 전체 CLUSTERDOWN | 실험 H, 요청별 slot 기록 |
| 5 | Spring Boot + Lettuce 는 토폴로지 변경을 언제 아는가 | Sentinel: `+switch-master` 구독으로 승격 직후(< 1 s). Cluster: 갱신 설정이 없으면 MOVED 를 받을 때까지(수십 초 가능), adaptive+periodic 갱신이면 승격 후 수 초 | Lettuce EventBus 이벤트 시각(T4), 설정별 오류율 |
| 6 | 성공 응답을 받은 쓰기가 유실될 수 있는가 | **있다.** 복제가 비동기라 Primary 가 ACK 한 뒤 Replica 에 못 간 쓰기는 승격 후 사라진다. 복제 지연을 주면 유실 건수가 지연에 비례 | 실험 F, 순번(seq) 대조 |
| 7 | 재시도로 `INCR` 이 중복 실행되는가 | **있다.** Lettuce 는 응답을 못 받은 명령을 재연결 후 다시 보낸다(command replay). 멱등 키 + Lua 는 중복 0 | 최종 카운터 값 − ACK 건수 |
| 8 | 복제 지연 상태에서 Primary 가 죽으면 | 지연 폭만큼 유실. `min-replicas-max-lag` 는 초 단위라 ms 지연은 못 막고, `WAIT 1` 은 유실 0 대신 쓰기 지연 증가 | 실험 F + 9절 설정 비교 |
| 9 | 옛 Primary 가 돌아오면 Primary 인가 Replica 인가 | **Replica 로 합류.** Sentinel 은 `+convert-to-slave`, Cluster 는 더 높은 configEpoch 를 보고 스스로 Replica 가 됨. 분기된 쓰기는 버려짐(full resync) | 실험 G, `INFO replication`, 로그 |
| 10 | 복구 시간·처리량·오류율·운영 복잡도 차이 | 정상 TPS 는 둘 다 앱/k6 한계에 묶여 비슷. 중단 시간은 timeout 설정이 지배. Cluster 는 장애 격리·확장에 유리, 멀티키·운영은 어려움 | 18절 표, 5회 반복 |

---

## 2. Sentinel 과 Cluster 의 차이

| 항목 | Redis Sentinel | Redis Cluster |
|---|---|---|
| 푸는 문제 | Primary 1대의 SPOF → 자동 Failover | SPOF + **데이터 분산**(16,384 slot) |
| 데이터 배치 | Primary 1대에 전부 | Primary 마다 slot 범위 |
| 감시 주체 | Sentinel 프로세스(별도) | 노드끼리 gossip(cluster bus, 포트+10000) |
| 장애 판단 | SDOWN(개별) → ODOWN(quorum) | PFAIL(개별) → FAIL(Primary 과반 보고) |
| 승격 | Sentinel 리더(과반 선출)가 Replica 에 `REPLICAOF NO ONE` | Replica 가 스스로 선거, Primary 과반의 투표 |
| 클라이언트 | Sentinel 에 "지금 Primary 누구?" 물어봄. **프록시 아님** | 노드가 MOVED/ASK 로 안내, 클라이언트가 slot 맵 유지 |
| 멀티키 | 자유 | 같은 slot(Hash Tag)만. 아니면 CROSSSLOT |
| 최소 노드 | Redis 3 + Sentinel 3 (실제 프로세스 6, 장비 3) | 6 (Primary 3 + Replica 3) |
| Split brain 방어 | `min-replicas-to-write` 로 보완 필요 | 과반 못 보는 Primary 는 `cluster-node-timeout` 후 쓰기 거부 |
| 운영 복잡도 | 낮음 | 높음(slot 재분배, 노드 교체) |

**Sentinel 은 프록시가 아니다.** 앱은 Sentinel 에 `SENTINEL get-master-addr-by-name` 으로 주소를 물은 뒤 **Redis 에 직접** 붙는다. Sentinel 장애와 Redis 장애가 다르게 보이는 이유이자(6절), 정적 프록시(Toxiproxy)를 앱-Primary 사이에 고정해 둘 수 없는 이유다(9절).

---

## 3. 전체 아키텍처

```text
호스트(macOS)                         Docker Compose 네트워크 lab-net (172.28.0.0/16, 고정 IP)
┌──────────────┐  :8085   ┌─────────────────────────────────────────────────────────────┐
│ k6           │──HTTP──▶ │ app (Spring Boot 3, Lettuce)  ──▶ Redis 노드들 (프로필별)     │
│ React(Vite)  │──/api──▶ │  ├ ChatService(공통 인터페이스)  Sentinel: r-a-1,2,3 + s-1,2,3│
│ :5182        │          │  ├ WorkloadRunner               Cluster : c-1..c-6           │
└──────────────┘          │  ├ ExperimentService ──▶ docker-socket-proxy ──▶ Docker API  │
                          │  ├ FaultExecutor    ──▶ fault-agent(sidecar, iptables/tc)   │
                          │  │                  ──▶ toxiproxy(앱↔Sentinel 경로)          │
                          │  ├ TopologyWatcher(Sentinel pubsub / CLUSTER NODES 폴링)     │
                          │  └ RecordingService ──▶ MySQL 8 (:3309, 실험 기록 전용)       │
                          │ redis_exporter ─▶ Prometheus(:9093) ─▶ Grafana(:3003)        │
                          └─────────────────────────────────────────────────────────────┘
```

**결정 1. 앱을 Docker 네트워크 안에서 실행한다.**
- 문제: Sentinel 과 Cluster 는 클라이언트에게 **노드의 실제 주소**(컨테이너 IP)를 알려준다. macOS 호스트는 컨테이너 IP 에 닿지 못한다.
- 대안: `cluster-announce-ip 127.0.0.1` + 호스트 포트 매핑. Sentinel 은 이런 우회가 없어 결국 안 된다.
- 선택: 앱을 컨테이너로 띄운다. k6·React 는 호스트에서 앱의 공개 포트(8085)만 본다.
- 단점: 코드 수정마다 이미지 재빌드. 완화: `bootJar` 레이어 이미지 + `docker compose up -d --build app`.
- 부수 이득: 앱·Redis·Sentinel 이 **같은 VM 시계**를 써서 T0~T6 를 한 시계로 비교할 수 있다.

**결정 2. 노드는 고정 IP.** 컨테이너 재시작 시 IP 가 바뀌면 Sentinel/Cluster 가 옛 주소를 들고 있을 수 있다. `ipv4_address` 로 고정하고 iptables 규칙도 IP 로 쓴다.

**결정 3. MySQL 은 실험 기록 전용.** 채팅 데이터는 전부 Redis. 기록 방식 3종(메모리 집계, 비동기 배치, 샘플링)을 부하 영향으로 비교한다(15절).

호스트 공개 포트(이 장비의 기존 프로젝트와 겹치지 않게): 앱 8085, 웹 5182, MySQL 3309, Prometheus 9093, Grafana 3003, Toxiproxy API 8474, Sentinel 모드 Redis 6390~6392 / Sentinel 26390~26392, Cluster 7100~7105. (Cluster 는 MOVED 가 컨테이너 IP 를 가리키므로 호스트 `redis-cli` 대신 `docker exec` 를 쓴다.)

---

## 4. Sentinel Docker Compose 구성 (프로필 `sentinel`)

```yaml
# docker/compose.yml 발췌 — 실제 파일은 구현 단계에서 작성
x-redis: &redis
  image: redis:7.4
  deploy: { resources: { limits: { cpus: "1.0", memory: 512M } } }
  networks: [lab-net]
services:
  r-a-1: { <<: *redis, command: ["redis-server", "/conf/redis.conf"],                         networks: { lab-net: { ipv4_address: 172.28.1.11 } }, profiles: [sentinel] }
  r-a-2: { <<: *redis, command: ["redis-server", "/conf/redis.conf", "--replicaof", "172.28.1.11", "6379"], networks: { lab-net: { ipv4_address: 172.28.1.12 } }, profiles: [sentinel] }
  r-a-3: { <<: *redis, command: ["redis-server", "/conf/redis.conf", "--replicaof", "172.28.1.11", "6379"], networks: { lab-net: { ipv4_address: 172.28.1.13 } }, profiles: [sentinel] }
  s-1:   { <<: *redis, entrypoint: ["/conf/sentinel-entrypoint.sh"], networks: { lab-net: { ipv4_address: 172.28.1.21 } }, profiles: [sentinel] }
  s-2:   # 172.28.1.22
  s-3:   # 172.28.1.23
  fault-r-a-1: { build: docker/fault-agent, network_mode: "service:r-a-1", cap_add: [NET_ADMIN], profiles: [sentinel] }
  # fault-r-a-2, fault-r-a-3 동일 (노드의 네트워크 네임스페이스를 공유하는 사이드카)
```

`sentinel.conf` 템플릿(Sentinel 은 자기 설정 파일을 다시 쓰므로 entrypoint 가 템플릿을 복사한 뒤 실행):

```conf
port 26379
sentinel resolve-hostnames yes
sentinel announce-hostnames no          # 앱이 IP 를 받도록. 고정 IP 라 문제 없음
sentinel monitor chat-primary 172.28.1.11 6379 2   # quorum 2
sentinel down-after-milliseconds chat-primary 5000  # 실험 기본 5 s (기본값 30 s). 1 s / 5 s / 15 s 민감도 실험
sentinel failover-timeout chat-primary 20000
sentinel parallel-syncs chat-primary 1
sentinel master-reboot-down-after-period chat-primary 0   # 실험 G 에서 10000 과 비교
```

검토 설정과 의미:

| 설정 | 의미 | 실험에서 보는 것 |
|---|---|---|
| `sentinel monitor <name> <ip> <port> <quorum>` | 감시 대상과 ODOWN 에 필요한 동의 수 | Spring 의 master name 과 일치해야 함 |
| `quorum` | ODOWN 판단 동의 수. **Failover 실행은 별개로 Sentinel 과반** 필요 | 실험 E: Sentinel 2대 중지 → ODOWN 불가 |
| `down-after-milliseconds` | 응답 없음 → SDOWN 까지 시간 | 감지 시간(T1−T0)의 지배 항 |
| `failover-timeout` | 같은 Primary 에 대한 재시도 간격, 승격 대기 상한 | 실패한 Failover 의 재시도 지연 |
| `parallel-syncs` | 승격 후 동시에 재동기화할 Replica 수 | 1 이면 Replica 가 순차로 잠깐씩 읽기 불가 |
| `replica-priority`(redis.conf) | 낮을수록 우선 승격, 0 은 승격 금지 | 승격 대상 예측 검증 |
| `master-reboot-down-after-period` | 재부팅 감지 시 그 기간 동안 down 취급 | 실험 G: 옛 Primary 가 빈 상태로 재기동될 때 |

**앱 설정**: `spring.data.redis.sentinel.master=chat-primary`, `nodes=172.28.1.21:26379,172.28.1.22:26379,172.28.1.23:26379`. 앱↔Sentinel 경로만 Toxiproxy 를 거치게 하는 변형 프로필(`sentinel-toxi`)을 둔다(주소가 정적이라 프록시가 가능).

**확인 명령**

```bash
docker exec s-1 redis-cli -p 26379 SENTINEL masters
docker exec s-1 redis-cli -p 26379 SENTINEL replicas chat-primary
docker exec s-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name chat-primary
docker exec r-a-1 redis-cli ROLE
docker exec r-a-2 redis-cli INFO replication      # master_link_status, master_repl_offset
docker exec s-1 redis-cli -p 26379 PSUBSCRIBE '*' # +sdown +odown +switch-master ... 실시간
```

---

## 5. Cluster Docker Compose 구성 (프로필 `cluster`)

```yaml
  c-1..c-6: image redis:7.4, 172.28.2.11 ~ 172.28.2.16, 같은 자원 제한
    command: redis-server /conf/redis.conf --cluster-enabled yes --cluster-node-timeout 5000
             --cluster-require-full-coverage yes --cluster-replica-validity-factor 0 --appendonly no
  cluster-init: redis:7.4 (1회 실행)
    command: redis-cli --cluster create 172.28.2.11:6379 ... 172.28.2.16:6379 --cluster-replicas 1 --cluster-yes
  fault-c-1..6: fault-agent 사이드카
```

`--cluster create` 는 앞 3개를 Primary, 뒤 3개를 Replica 로 배정한다(c-1↔c-4, c-2↔c-5, c-3↔c-6 매핑은 생성 시 출력을 기록해 두고 `CLUSTER NODES` 로 확인). slot 은 0–5460 / 5461–10922 / 10923–16383.

확인 항목과 명령:

```bash
docker exec c-1 redis-cli CLUSTER INFO     # cluster_state, cluster_slots_assigned, cluster_known_nodes, cluster_size, cluster_current_epoch
docker exec c-1 redis-cli CLUSTER NODES    # 노드별 role, master id, slot 범위, flags(fail?, fail)
docker exec c-1 redis-cli CLUSTER SLOTS
docker exec c-1 redis-cli --cluster check 172.28.2.11:6379
docker exec c-1 redis-cli CLUSTER KEYSLOT 'room:{42}:seq'
docker exec c-1 redis-cli -c GET user:1:presence     # -c 로 MOVED 자동 추종 관찰
```

Cluster 전용 설정:

| 설정 | 실험 기본값 | 실험에서 보는 것 |
|---|---|---|
| `cluster-node-timeout` | 5000 | PFAIL→FAIL 감지 시간. 과반 못 보는 Primary 가 쓰기를 거부하는 시간 |
| `cluster-require-full-coverage` | yes ↔ no 비교 | 실험 H: 한 slot 범위가 비었을 때 전체 정지 vs 부분 격리 |
| `cluster-replica-validity-factor` | 0 | 오래 끊긴 Replica 도 승격 허용(실험 F 에서 지연 Replica 승격을 보기 위해) |
| `cluster-allow-reads-when-down` | no ↔ yes | 실험 E: 과반 붕괴 시 읽기 허용 여부 |
| `cluster-migration-barrier` | 1 | Replica 재배치 조건(참고) |

---

## 6. Spring Boot 와 Lettuce 설정

Spring Boot 3.5.x(Java 21), Spring Data Redis, Lettuce 6.6.x(Boot 관리 버전. `replayFilter` 유무는 1단계에서 확인). 프로필: `single`(1단계), `sentinel`, `cluster`, 그리고 실험 API 를 켜는 `local-experiment`.

### Sentinel (`application-sentinel.yml`)

```yaml
spring.data.redis:
  sentinel: { master: chat-primary, nodes: "172.28.1.21:26379,172.28.1.22:26379,172.28.1.23:26379", password: "${REDIS_SENTINEL_PASSWORD:}" }
  password: "${REDIS_PASSWORD:}"       # 노드 인증(requirepass) — 실험은 비활성이 기본, 인증 켠 프로필도 1회 검증
  timeout: 1s                         # command timeout (양쪽 구성 동일)
  connect-timeout: 1s
  lettuce: { shutdown-timeout: 200ms, pool: { enabled: false } }   # 기본은 공유 Native Connection
```

- 새 Primary 탐색: Lettuce 는 나열된 Sentinel 중 하나에 접속해 `get-master-addr-by-name` 으로 주소를 얻고, 각 Sentinel 의 pubsub `+switch-master` 를 구독해 승격을 알아챈다. 연결이 끊기면 재연결 때마다 Sentinel 에 다시 묻는다.
- **Sentinel 장애 vs Redis 장애 구분**: Sentinel 1대 다운 → 다음 Sentinel 로 넘어감(무영향 예상). Sentinel 3대 전부 다운 → 기존 연결은 계속 동작, **새 주소 조회만 불가**. 그 상태에서 Primary 가 죽으면 Failover 자체가 없음. 실험 E 로 확인.

### Cluster (`application-cluster.yml`)

```yaml
spring.data.redis:
  cluster: { nodes: "172.28.2.11:6379,172.28.2.12:6379,172.28.2.13:6379", max-redirects: 3 }   # seed 는 일부만. 죽은 seed 포함 실험 별도
  timeout: 1s
  lettuce.cluster.refresh: { adaptive: true, period: 5s, dynamic-refresh-sources: true }
```

Lettuce 옵션 비교 실험(변수 하나씩):

| 옵션 | 기본 | 비교값 | 가설 |
|---|---|---|---|
| Adaptive refresh | off | on (MOVED, ASK, PERSISTENT_RECONNECTS, UNCOVERED_SLOT, UNKNOWN_NODE) | off 면 승격 후에도 죽은 노드로 재연결만 반복. `adaptiveRefreshTriggersTimeout`(기본 30 s) 이 갱신을 묶어 두는지 확인 |
| Periodic refresh | off | 5 s / 30 s / 60 s | 주기가 곧 최악의 인식 지연 |
| `autoReconnect` | on | off | off 면 연결 끊김 후 영구 실패 |
| command timeout | 1 s | 200 ms / 5 s | 짧을수록 장애 중 실패가 빨리 드러나고 타임아웃 수는 증가 |
| `disconnectedBehavior` | DEFAULT(큐잉) | REJECT_COMMANDS | DEFAULT 는 장애 중 명령이 큐에 쌓였다가 재연결 후 실행 → "늦은 성공", 비멱등 중복 위험 |
| 재시도(앱 레벨) | 없음 | GET 만 재시도 / 전부 재시도 | INCR 재시도 = 중복 |
| 공유 Native Connection ↔ Pool | 공유 | commons-pool2 8 | 처리량·재연결 동작 차이 |

**재시도 분석 원칙.** `GET` 은 몇 번 실행돼도 결과가 같다. `INCR`, 재고 차감, `XADD` 는 실행 횟수가 곧 결과다. 응답 유실(서버는 실행했지만 클라이언트는 못 받음) 상황에서 재시도는 곧 중복이다. 해법은 "재시도를 안 한다"가 아니라 **요청 ID 로 멱등화**(Lua 에서 dedupe 키 검사 + 상태 변경을 한 스크립트로) 이며, 이를 실험 7(질문 7)로 검증한다.

---

## 7. 공통 API 및 워크로드

### 채팅 도메인 → 키 설계 (스펙의 세 가지 키 유형에 대응)

| 유형 | 키 | 명령 | 채팅 의미 |
|---|---|---|---|
| 분산 키 | `user:<id>:presence` | `SET … EX 30` / `GET` | 접속 상태 하트비트. slot 이 골고루 퍼짐 |
| 분산 키, 비멱등 | `user:<id>:unread` | `INCR` | 안 읽은 수. 재시도 중복이 값으로 드러남 |
| Hash Tag 키 | `room:{<id>}:seq`, `room:{<id>}:stream`, `room:{<id>}:dedupe:<clientMsgId>` | Lua 1회 | 메시지 전송 = 순번 발급 + 저장 + 멱등 키. 같은 slot 이라 원자 실행 가능 |
| Hash Tag 키 | `room:{<id>}:members`, `room:{<id>}:slots` | Lua | 정원 있는 방 입장 = 원자적 재고 감소 |
| 멀티키 읽기 | `user:1:presence`, `user:2:presence`, … | `MGET` | 방 멤버 접속 상태 일괄 조회. Cluster 에서 Lettuce 가 slot 별로 쪼개 보냄 |

Hash Tag 전(`room:42:seq`, `room:42:stream` — slot 이 다름) 버전의 Lua 를 그대로 Cluster 에 보내 **CROSSSLOT 을 재현**하고, Hash Tag 적용 후와 비교한다(13절). 사용자 소유 키(`user:<id>:rooms`)와 방 키를 한 스크립트에 넣어야 하는 "입장" 기능은 Hash Tag 로 못 묶는 사례로 남겨, 방 쪽 원자 처리 + 사용자 쪽 멱등 쓰기로 나누는 설계를 ADR 로 기록한다.

### 공통 인터페이스

```java
interface ChatStore {                 // 구현 1개(RedisTemplate/Lettuce) + 프로필별 ConnectionFactory
  String getPresence(long userId);
  void setPresence(long userId, String status);            // 멱등 SET
  long incrUnread(long userId);                            // 비멱등 INCR
  SendResult sendMessage(long roomId, String clientMsgId, String body); // Lua, 멱등
  JoinResult join(long roomId, long userId);               // Lua, 원자 정원 감소
  List<String> presenceOf(List<Long> userIds);             // MGET
}
```

### 워크로드 실행기

앱 안의 `WorkloadRunner` 가 초당 N건을 토큰 버킷으로 발생시키며 명령 비율(GET/SET/INCR/SEND/MGET, 기본 50/20/10/15/5)과 키 범위(사용자 10,000, 방 100)를 받는다. 모든 요청은 `experimentId`, `requestId`, `sequenceNumber`, `key`, `requestedAt`, `acknowledgedAt`, `responseStatus`(OK/TIMEOUT/CONNECTION/MOVED/ASK/CROSSSLOT/CLUSTERDOWN/READONLY/OTHER) 를 갖는다. k6 도 같은 서비스 메서드를 HTTP 로 호출하므로 두 경로가 같은 기록 체계를 공유한다.

### API

```http
POST /api/workloads/start      { mode: "performance|consistency", rps, mix:{get,set,incr,send,mget}, users, rooms }
POST /api/workloads/stop
GET  /api/workloads/status     { running, rps, totals, lastSecond:{ok,fail,p95} }

POST /api/experiments                       { name, topology:"sentinel|cluster", hypothesis, workload:{...}, recordingMode }
POST /api/experiments/{id}/inject-failure   { scenario:"KILL_PRIMARY", target:"primary", params:{delayMs}, confirm:true }
POST /api/experiments/{id}/recover          { confirm:true }
GET  /api/experiments                       목록
GET  /api/experiments/{id}                  T0~T6, 지표, 유실·중복 계산, 타임라인 이벤트
GET  /api/experiments/{id}/export?format=json|csv

GET  /api/redis/topology       역할·slot·offset·lag·상태(UP/DOWN/SUSPECTED/FAILOVER/SYNCING)
GET  /api/redis/nodes          노드별 INFO 요약(cpu, mem, connected_slaves, master_link_status)
GET  /api/redis/metrics        앱 측 명령 지표 스냅샷
GET  /api/redis/events/stream  SSE: topology, sample(1 s 집계), event(Sentinel/Cluster 이벤트), fault
```

HTTP 워크로드 엔드포인트(k6 용): `POST /api/chat/rooms/{roomId}/messages`, `PUT /api/chat/users/{id}/presence`, `GET /api/chat/users/{id}/presence`, `POST /api/chat/users/{id}/unread`, `GET /api/chat/rooms/{roomId}/presence`.

---

## 8. React 실시간 대시보드

React 19 + TypeScript + Vite + TanStack Query + Recharts. 스타일은 frontend-design 스킬로 별도 설계(Tailwind 는 쓰지 않을 가능성이 큼 — 결정은 구현 단계).

**SSE 를 선택한다.** 흐름이 서버→브라우저 단방향(토폴로지, 1초 집계, 이벤트)이고 명령은 REST POST 로 충분하다. SSE 는 Spring MVC `SseEmitter` 로 끝나고, 브라우저가 끊기면 스스로 재접속하며(`Last-Event-ID`), Vite 프록시를 그대로 지난다. WebSocket 은 양방향·고빈도(수백 msg/s 이상)일 때 이점이 있는데 여기선 초당 수 개 이벤트다. 단점: HTTP/1.1 에서 브라우저당 연결 수 제한(6) — 탭 하나만 쓰므로 무관.

화면:
1. **토폴로지** — 노드 카드(이름, IP:port, 역할 PRIMARY 빨강/REPLICA 파랑/SENTINEL 보라, 상태 UP/DOWN/SUSPECTED/FAILOVER/SYNCING, 연결된 Primary/Replica, slot 범위, replication offset·lag, CPU·메모리, 마지막 변경 시각). Cluster 는 Primary→Replica 를 선으로.
2. **실험 제어** — 구성 선택(sentinel/cluster 는 실행 중 프로필로 고정 표시), RPS, 명령 비율, 워크로드 시작·중지, 장애 대상(논리 이름만), 시나리오 버튼(Primary 종료/강제 종료/일시정지, 복제 지연, 파티션, 복구, 전체 초기화). 위험 동작은 확인 모달 + 자동 복구 타이머 표시.
3. **실시간 차트** — 초당 성공·실패, p95·p99, 현재 Primary, Failover 타임라인(T0~T6 마커), replication lag/offset 차, 노드별 메모리, 승인 쓰기 유실 누계, 오류 종류별, slot 범위별 요청량(Cluster).
4. **실험 결과** — T0~T6, 중단 시간, 전체/실패 요청, 오류율, 유실·중복 수, 같은 이름의 Sentinel/Cluster 실험 나란히 비교, JSON·CSV 내보내기.

---

## 9. 장애 주입 구조

```text
React → POST /api/experiments/{id}/inject-failure {scenario, target, params, confirm}
      → ExperimentService (local-experiment 프로필에서만 빈 등록, 관리자 토큰 헤더 검사, 동시 1개, 최대 지속 120 s, 자동 복구 타이머, 이력 저장)
      → FaultScenario(enum) → FaultPlan(고정된 컨테이너 이름·고정 argv 목록)
      → DockerFaultExecutor ── docker-socket-proxy(내부 네트워크 전용, CONTAINERS/EXEC 만 허용) ── Docker API (stop/kill/pause/start, exec)
      → NetworkFaultExecutor ── fault-agent 사이드카의 고정 스크립트(iptables/tc) exec
      → ToxiproxyExecutor ── Toxiproxy HTTP API (앱↔Sentinel 프록시에만)
```

허용 시나리오(enum, 구성별로 실제 컨테이너 이름은 정적 표에서만 조회):

```text
STOP_PRIMARY, KILL_PRIMARY, PAUSE_PRIMARY, STOP_REPLICA, KILL_REPLICA, STOP_SENTINEL, STOP_ALL_SENTINELS,
STOP_PRIMARY_AND_ITS_REPLICA (Cluster, 실험 H), RESTORE_NODE, RESTORE_ALL,
PARTITION_PRIMARY_FROM_SENTINELS, PARTITION_PRIMARY_FROM_REPLICAS, PARTITION_PRIMARY_FROM_APP, PARTITION_PRIMARY_FROM_PEERS (Cluster),
NETEM_REPLICATION_DELAY(ms 100~2000), NETEM_GLOBAL_DELAY(ms), NETEM_LOSS(pct 1~30),
TOXIC_APP_SENTINEL_TIMEOUT, TOXIC_APP_SENTINEL_LATENCY(ms), REMOVE_TOXICS, CLEAR_NETWORK_FAULTS
```

**왜 Toxiproxy 만으로 안 되는가.** Sentinel/Cluster 는 클라이언트와 Replica 에게 노드의 실제 주소를 알려주므로, 앱↔Primary·Replica↔Primary 사이에 고정 프록시를 끼워 넣어도 Failover 뒤 우회된다(Sentinel 은 "잘못된 master 를 보는 Replica" 를 `REPLICAOF` 로 되돌리기까지 한다). 그래서 **노드의 네트워크 네임스페이스를 공유하는 사이드카에서 iptables(상대별 차단)·tc netem(지연·손실)** 로 링크 단위 장애를 만들고, Toxiproxy 는 주소가 정적인 **앱↔Sentinel 경로** 에만 쓴다. 이 제약 자체가 "Sentinel 은 프록시가 아니다" 를 보여 주는 결과다. (`tc netem` 이 Docker Desktop 커널에서 동작하는지는 1단계에서 확인. 안 되면 Toxiproxy 로 Sentinel 경로만 하고 복제 지연은 `docker pause` 기반 대안으로 대체.)

안전장치: `@Profile("local-experiment")` 없이는 컨트롤러가 뜨지 않음. `X-Lab-Admin-Token` 검사. 요청 본문에 컨테이너 이름·명령 문자열 필드가 없고 enum 과 정수 파라미터(범위 검사)만 받음. 실행 중 실험 1개(`AtomicReference`). 장애 시작 시 자동 복구 타이머(기본 120 s) 등록. 실행기 예외 시 `RESTORE_ALL` + `CLEAR_NETWORK_FAULTS` 시도. 모든 호출은 `fault_action` 테이블에 기록.

---

## 10. 기본 Primary 승계 실험

공통: 워크로드 500 RPS(GET 50/SET 20/INCR 10/SEND 15/MGET 5), `consistency` 기록 모드, 워밍업 1분 후 시작.

### Sentinel

| 단계 | 하는 일 | 확인 명령 / 기록 |
|---|---|---|
| 1 | Primary·Replica 상태 확인 | `SENTINEL masters`, `ROLE`, `INFO replication` (offset 3개 일치) |
| 2 | SET/GET/INCR/SEND 지속 전송 | 대시보드 초당 성공 확인 |
| 3 | Primary 강제 종료(`KILL_PRIMARY`) | T0 = Docker API 응답 시각 |
| 4 | SDOWN/ODOWN 시점 | Sentinel pubsub `+sdown`(각 Sentinel), `+odown` → T1 |
| 5 | 승격 대상 | `+selected-slave`, `+promoted-slave` → T2/T3, `SENTINEL get-master-addr-by-name` |
| 6 | 나머지 Replica 재복제 | `+slave-reconf-done`, `INFO replication` `master_host` 변경 → T6 |
| 7 | 앱 재연결 | Lettuce `ConnectedEvent`(새 주소) → T4, 첫 SEND 성공 → T5 |
| 8 | 옛 Primary 재시작(`RESTORE_NODE`) | 로그 "Partial/Full resync" |
| 9 | Replica 로 합류 확인 | `+convert-to-slave`, `ROLE` → slave |

### Cluster

| 단계 | 하는 일 | 확인 |
|---|---|---|
| 1–2 | Primary↔Replica 매핑, slot 분포 | `CLUSTER NODES`, `CLUSTER SLOTS` |
| 3 | 여러 slot 에 지속 요청 | 요청별 `CLUSTER KEYSLOT` 사전 계산으로 slot 범위 태그 |
| 4 | slot 0–5460 담당 Primary 종료 | T0 |
| 5 | Replica 승격 | 생존 노드 `CLUSTER NODES` 의 `fail?`→`fail`(T1), `cluster_current_epoch` 증가(T2), 해당 Replica `master` 전환(T3); 노드 로그 "Failover election won" 로 보정 |
| 6 | slot 범위 유지 | 승격 노드의 slot 이 0–5460 그대로인지 |
| 7 | 다른 slot 영향 | 5461–16383 요청의 오류율이 0 에 가까운지(가설: 영향 없음) |
| 8 | 앱 토폴로지 재조회 | `ClusterTopologyChangedEvent` → T4, 해당 slot 첫 성공 → T5 |
| 9–10 | 옛 Primary 재시작, Replica 합류·재동기화 | `CLUSTER NODES` 에 `slave` 표시, `INFO replication`, 로그 "MASTER <-> REPLICA sync started" |

---

## 11. 네트워크 파티션과 Quorum 실험

| 실험 | 방법 | 가설 |
|---|---|---|
| A 정상 종료 | `STOP_PRIMARY`(SIGTERM) | Redis 는 종료 시 연결을 닫는다. 앱은 즉시 연결 오류를 보지만 Sentinel/Cluster 감지는 여전히 timeout 만큼 걸림 → 중단 시간은 B 와 비슷 |
| B 비정상 종료 | `KILL_PRIMARY`(SIGKILL) | 커널이 RST 를 보내므로 A 와 거의 같음. **`PAUSE_PRIMARY`(프로세스 정지)** 는 연결이 살아 있어 앱은 command timeout 까지 모름 → 가장 나쁜 사례 |
| C-1 복제 단절 | 사이드카 iptables 로 Primary↔Replica 차단 | Failover 없음(Sentinel 은 Primary 를 볼 수 있음). Replica 만 `master_link_status:down`. `min-replicas-to-write 1` 이면 Primary 가 쓰기 거부 |
| C-2 Primary↔Sentinel 단절 | Primary 사이드카에서 Sentinel 3개 IP 차단 | Sentinel 은 ODOWN → 승격. 앱은 `+switch-master` 로 새 Primary 로 이동. **옛 Primary 는 계속 살아 있으므로 앱이 옮기기 전 쓴 값은 재합류 때 버려짐** = Split brain 창. `min-replicas-to-write` 로 창 축소 |
| C-3 Primary↔Cluster 노드 단절 | 다른 5개 노드 차단(bus·client 포트) | 나머지가 FAIL 판정·승격. 고립 Primary 는 `cluster-node-timeout` 후 과반 상실로 `CLUSTERDOWN` 반환 → 자체 방어. 창 = node-timeout |
| C-4 앱↔Primary 만 단절 | Primary 사이드카에서 앱 IP 차단 | **Failover 없음**(Sentinel/Cluster 는 정상으로 봄). 앱만 실패. "클라이언트 관점 장애 ≠ 서버 관점 장애" |
| C-5 전체 지연·손실 | netem 200 ms / loss 5 % | 감지 시간은 그대로, 요청 p95 상승, 손실률에 따라 타임아웃 증가 |
| D Replica 선행 장애 | Replica 1대 종료 → Primary 종료 | Sentinel: 남은 Replica 1대로 승격 가능. Replica 2대 모두 종료 후 Primary 종료 → 승격 불가, 복구까지 전면 중단. Cluster: 담당 Replica 가 죽은 뒤 Primary 가 죽으면 그 slot 은 비고 `require-full-coverage yes` 면 전체 정지 |
| E Quorum 부족 | Sentinel 2/3 중지, 또는 Cluster Primary 2/3 중지 | Sentinel: 남은 1대는 SDOWN 만, ODOWN·Failover 없음. 읽기·쓰기는 Primary 가 살아 있으면 정상. Cluster: 남은 Primary 도 과반 없음 → 전체 `cluster_state:fail`, 선거 불가. 복구 후 상태 회복 시간 기록 |

프로세스 종료와 파티션이 다른 이유: 종료는 "모두가 동시에 못 보는" 장애, 파티션은 "누가 보느냐에 따라 다른" 장애다. C-2·C-4 처럼 감시자와 클라이언트의 시각이 갈리면 Failover 가 없거나 이중 Primary 창이 생긴다.

---

## 12. 승인 쓰기 유실 실험 (실험 F·G)

**측정 방법.** `consistency` 모드에서 모든 쓰기에 `sequenceNumber`(실험 내 단조 증가)를 붙이고, `SEND` 는 Lua 가 발급한 방별 `seq` 를 응답에 담아 ACK 로 기록한다. Failover 후 T6 에 `room:{r}:stream` 을 전부 읽어 대조한다.

```text
승인된 쓰기 수(A)            = 장애 전 responseStatus=OK 인 쓰기
복구 후 존재하는 승인 쓰기(B) = 스트림/키에 실제 남은 A 의 원소
승인 후 유실(L)              = A − B
마지막 보존 sequenceNumber   = max(B)
중복 반영된 INCR              = 최종 unread 값 − 해당 키 INCR ACK 수 (양수면 중복, 음수면 유실)
잘못된 값으로 복구된 키       = presence 의 마지막 ACK 값 ≠ 복구 후 값
승인 쓰기 유실률(%)          = L / A × 100
```

**절차(F).** ① `NETEM_REPLICATION_DELAY 500ms`(Primary→Replica 방향만) ② SEND/INCR 집중 1,000 RPS ③ `master_repl_offset` − Replica `slave_repl_offset` 를 1초마다 기록 ④ `KILL_PRIMARY` ⑤ 승격 후 대조.
가설: 유실 ≈ 지연(0.5 s) × 쓰기율. 지연 0 이어도 수 건은 나올 수 있다(비동기).

**설정 비교(같은 F 절차, 각 5회).** 표의 값은 실측 후 채운다.

| 설정 | 데이터 안전성(유실 수) | 쓰기 p95 | 가용성(장애 중 쓰기 거부 여부) | 예상 |
|---|---:|---:|---|---|
| 기본 비동기 복제 | 미측정 | 미측정 | 계속 받음 | 유실 > 0 |
| `min-replicas-to-write 1` + `min-replicas-max-lag 1` | | | Replica 응답 1 s 이상 늦으면 거부 | ms 지연은 못 막아 유실 그대로. C-2 split brain 창은 막음 |
| `WAIT 1 1000` 을 SEND 뒤에 호출 | | | 대기 초과 시 앱이 실패 처리 | 유실 0 (승격 대상은 offset 최대 Replica), p95 = 복제 RTT 만큼 증가 |
| AOF off / `everysec` / `always` | | | | **유실 수는 변하지 않음**(복제 문제), `always` 는 p95 증가. 옛 Primary 재기동 시 로컬 데이터 유무만 다름 |

Cluster 는 `WAIT` 를 키가 속한 Primary 연결에서 보내야 한다(Lettuce `getConnection(nodeId)`). 구현 시 확인.

**G 옛 Primary 복구.** 재시작 → `INFO replication` 의 `master_replid`/`second_repl_offset` 와 로그로 partial/full resync 판정 → 분기 쓰기(재합류 전 옛 Primary 가 받은 ACK) 가 버려지는지 → 복구 완료 시간. Sentinel `master-reboot-down-after-period` 0 ↔ 10 s 비교(옛 Primary 가 AOF 없이 빈 채로 뜨는 경우의 보호).

---

## 13. Hash Slot 과 CROSSSLOT 실험

1. `room:42:seq` + `room:42:stream` 로 SEND Lua 실행(Cluster) → `CROSSSLOT Keys in request don't hash to the same slot` 재현, `redis_command_failures_total{error="CROSSSLOT"}` 증가 확인.
2. `room:{42}:seq` + `room:{42}:stream` → `CLUSTER KEYSLOT` 동일 → 성공.
3. `MGET user:1:presence user:2:presence …`(다른 slot) 는 Lettuce 가 slot 별로 나눠 보내 성공하지만 `MULTI`/Lua 로 묶으면 실패 → "클라이언트가 대신 해주는 것과 서버 제약" 구분.
4. 이동 중 slot(`--cluster reshard` 로 slot 100개 이동)에 요청 → `ASK` redirect 관찰(Lettuce 가 자동 추종, `redis_cluster_redirect_total{type=ASK}`).
5. Sentinel 은 위 제약이 없음을 같은 코드로 확인(ADR 근거).

---

## 14. k6 부하 테스트

`constant-arrival-rate`(개방형 모델) 로 RPS 를 고정한다. VU 고정 방식은 장애 중 응답이 느려지면 요청률이 함께 떨어져 "동일 요청률" 조건이 깨진다.

```js
// k6/chat.js 개요 — 시나리오를 시간대별로 나눠 단계별 지표를 태그로 분리
warmup(0–60s) → steady(60–180s) → failure(180–300s) → recovery(300–420s)
각 단계: constant-arrival-rate, rate=RPS, preAllocatedVUs=RPS/10
```

벤치 스크립트(`bench/run.sh`)가 k6 를 띄우고 180 s 에 `inject-failure`, 300 s 에 `recover` 를 호출한다. RPS 단계 100 / 500 / 1,000 / 2,000, 각 5회, 실행 순서 교대(sentinel→cluster, cluster→sentinel). 명령 비율·데이터 크기(값 64 B)·키 범위·timeout·영속화·Docker 자원·장애 시점은 두 구성에서 동일.

---

## 15. 측정 지표

시각: `T0` 장애 주입(Docker API 응답), `T1` 감지(Sentinel `+odown` / Cluster `fail` 플래그), `T2` 승격 시작(`+failover-state-select-slave` / epoch 증가), `T3` 승격 완료(`+switch-master` / Replica 의 `role:master`), `T4` 앱 인식(Lettuce EventBus 새 주소 `ConnectedEvent` / `ClusterTopologyChangedEvent`), `T5` 첫 쓰기 성공, `T6` 안정화(모든 Replica `master_link_status:up` + Sentinel `num-slaves` 정상 / `cluster_state:ok` 이고 `CLUSTER NODES` 가 전 노드에서 일치).

```text
장애 감지 시간 = T1−T0, Replica 승격 시간 = T3−T2, 클라이언트 재연결 시간 = T5−T3,
서비스 중단 시간 = T5−T0, 토폴로지 안정화 시간 = T6−T0
```

폴링 해상도(200 ms)와 로그 타임스탬프를 함께 기록하고 오차를 표에 병기한다. 앱·Redis·Sentinel 이 같은 VM 시계를 쓰므로 교차 비교가 가능하다.

추가 항목: 단계별(전·중·후) TPS, 성공 TPS, 평균·p50·p95·p99, 타임아웃 수, 연결 실패 수, 명령 오류 수, `MOVED`/`ASK`/`CROSSSLOT` 수, 재시도 수, 중복 수, 승인 쓰기 유실 수, replication lag(초, `master_last_io_seconds_ago` 및 offset 차 바이트), CPU·메모리(cAdvisor 없이 `docker stats` 스트림), 네트워크 사용량(컨테이너 `/proc/net/dev` 차분), 앱 활성 연결 수(Lettuce `connected_clients` 를 노드에서 조회).

**기록 방식 비교(MySQL 영향).** ① 메모리 집계(LongAdder + Micrometer Timer) 후 1 s 마다 1행 ② 요청별 기록을 유계 큐 → 배치 INSERT(500행) ③ 1/100 샘플링. 같은 RPS 에서 Redis 명령 p95 와 앱 CPU 를 비교해 `performance` 모드 기본값을 고른다. `consistency` 모드는 ② 고정(전수 추적 필요).

---

## 16. Prometheus 와 Grafana

Micrometer 커스텀 메트릭:

| 메트릭 | 타입 | 이유 |
|---|---|---|
| `redis_commands_total{op,outcome}` | Counter | 누적 건수, rate() 로 초당 |
| `redis_command_duration_seconds{op}` | Timer(히스토그램) | p95/p99 |
| `redis_command_failures_total{op,error}` | Counter | 오류 종류별 |
| `redis_connection_failures_total`, `redis_reconnect_total`, `redis_topology_refresh_total` | Counter | Lettuce EventBus 에서 증가 |
| `redis_failover_total{topology}` | Counter | 관측된 Failover 수 |
| `redis_failover_duration_seconds{phase=detect\|promote\|reconnect\|total}` | Timer | T 차이 기록 |
| `redis_write_ack_total`, `redis_acknowledged_write_loss_total`, `redis_duplicate_operation_total` | Counter | 정합성 |
| `redis_replication_lag_bytes{node}` | Gauge | 순간값 |
| `redis_cluster_redirect_total{type}` | Counter | MOVED/ASK |
| `redis_node_up{node,role}` | Gauge | 0/1 |
| `workload_requests_total{op,outcome}`, `workload_request_duration_seconds{op}` | Counter / Timer | 워크로드 관점 |

Prometheus 는 앱(`/actuator/prometheus`) 과 `redis_exporter`(멀티 타겟 `/scrape?target=`) 를 1 s 로 긁는다. Grafana 패널: 노드 상태, 현재 Primary(annotation), 초당 명령, 성공률·오류율, p95·p99, Failover 타임라인(T0~T6 annotation), 복제 지연, 연결 실패, 토폴로지 갱신, 승인 쓰기 유실, 장애 전·중·후 비교.

---

## 17. 자동화 테스트

| 테스트 | 도구 | 타임아웃·실패 메시지 |
|---|---|---|
| 단일 Redis GET/SET/INCR/Lua | Testcontainers `redis:7.4` | 10 s |
| CROSSSLOT 재현·Hash Tag 성공 | Testcontainers 로 3-node cluster(컨테이너 3개 + `--cluster create`) | 30 s |
| 멱등 Lua 로 중복 방지 | Testcontainers | 같은 clientMsgId 2회 → seq 동일 |
| Sentinel Primary 종료 → 승격 | compose 스택 + 실험 API(`-Dlab.integration=sentinel`) | Awaitility `atMost(30 s)`, "30 s 안에 새 Primary 가 선출되지 않음(마지막 SENTINEL masters: …)" |
| Cluster Primary 종료 → 담당 Replica 승격, slot 유지 | compose + API | 30 s |
| 새 Primary 쓰기 수신, 앱 재연결 | 위와 연계 | 첫 성공 쓰기 20 s |
| 옛 Primary Replica 합류, 데이터 동기화 | 위와 연계 | 60 s |
| Quorum 부족 시 Failover 없음 | Sentinel 2대 중지 후 Primary 종료 | 40 s 동안 승격 **없음** 을 확인(부정 대기도 상한 명시) |
| 승인 쓰기 유실 | 복제 지연 + kill | 유실 계산 결과가 기록과 일치 |

무한 대기 금지: 모든 `await()` 에 `atMost` 와 `alias`/메시지. Testcontainers 로 Sentinel 전체를 띄우는 것은 무거워 compose 스택 대상 통합 테스트로 두고, CI 없이 로컬 태그로 실행한다.

---

## 18. 비교 결과표 (실측 후 채움)

| 지표 | Sentinel | Cluster | 분석 |
|---|---:|---:|---|
| 정상 상태 TPS | 미측정 | 미측정 | 예상: 둘 다 k6/앱 한계, 차이 작음 |
| 정상 상태 p95 | 미측정 | 미측정 | 예상: Cluster 가 소폭 높음(slot 계산·연결 수) |
| 장애 감지 시간 | 미측정 | 미측정 | 예상: 각각 down-after / node-timeout 근처 |
| Replica 승격 시간 | 미측정 | 미측정 | 예상: 1 s 내외 |
| 서비스 중단 시간 | 미측정 | 미측정 | 예상: Cluster 는 영향 slot 기준 |
| 장애 중 오류율 | 미측정 | 미측정 | 예상: Sentinel 100 % vs Cluster ≈ 33 % |
| 승인 쓰기 유실 수 | 미측정 | 미측정 | 예상: 둘 다 > 0, 지연 비례 |
| 중복 반영 수 | 미측정 | 미측정 | 예상: replay 설정에 의존 |
| 토폴로지 안정화 | 미측정 | 미측정 | |
| 정상화 후 TPS | 미측정 | 미측정 | |

조건별(Graceful Stop, SIGKILL, Pause, 파티션 C-1~C-5, 복제 지연, Quorum 부족, Primary 재합류, Primary+Replica 동시 장애) 로 같은 표를 반복하고, 5회 값·중앙값·최소·최대·표준편차를 병기한다. TPS 외 평가축: Failover 속도, 부분 장애 격리, 수평 확장성, 유실 가능성, 클라이언트 복잡도, 운영 난이도, 멀티키 제약, 최소 노드 수, 네트워크 비용(bus 트래픽).

---

## 19. 단계별 구현 로드맵

| 단계 | 내용 | 완료 조건 | 검증 |
|---|---|---|---|
| 1 단일 Redis + 워크로드 | compose(단일 Redis, MySQL, Prometheus, Grafana, socket-proxy, fault-agent 이미지), Spring `ChatStore`, 워크로드 실행기, 기록 3모드, SSE, React 골격, k6 기본 | 500 RPS 5분 무오류, 기록 모드별 p95 표, tc netem 동작 여부 확인 | Testcontainers 테스트 통과, 대시보드에 초당 그래프 |
| 2 Sentinel | Primary 1 + Replica 2 + Sentinel 3, Lettuce Sentinel, 이벤트 구독, 실험 A/B/G | 5회 반복 Failover 타임라인, 옛 Primary 가 Replica 로 합류 | 통합 테스트 2건, 타임라인 문서 |
| 3 Cluster | 6노드, Lettuce Cluster, 토폴로지 갱신 옵션, 실험 A/B/G/H, CROSSSLOT | slot 유지 검증, 갱신 설정별 오류율 표 | 통합 테스트 3건 |
| 4 비교·장애 실험 | 동일 부하 4단계 × 5회, C/D/E/F, 유실·중복, 재시도 정책 | 18절 표 전부 실측값, 설정 비교표 | `bench/report.py` 자동 생성 |
| 5 모니터링·문서 | Grafana 패널 확정, 타임라인 문서, ADR, README, 면접 질문 | 이력서 문장 A~D 실측값으로 채움 | 문서 팩트체크(수치 ↔ results) |

구현 순서(승인 후): Docker 인프라 → Spring 연결 → 공통 워크로드 → React 대시보드 → Sentinel 실험 → Cluster 실험 → 정합성·유실 실험 → 성능 측정 → 문서화.

---

## 20. README 와 면접 전략

README 이야기 구조: 단일 Redis SPOF → 복제 → Sentinel 자동 Failover → 강제 종료 검증 → Sentinel 의 확장 한계 → Cluster → slot 분산·부분 장애 → Lettuce 토폴로지 갱신 → 승인 쓰기 유실 검증 → 데이터 안전성 설정 비교 → 선택 기준(ADR).

이력서 문장 틀(A~D 는 같은 환경 실측값만):

```text
Redis Sentinel과 3-Primary·3-Replica Cluster 환경을 구축하고, 지속적인 쓰기 부하 중 Primary 노드를 강제로 종료하여 자동 Failover를 검증했습니다.
Sentinel과 Cluster의 서비스 중단 시간을 각각 A초와 B초로 측정했으며, Lettuce 토폴로지 갱신 설정을 개선해 장애 중 요청 오류율을 C%에서 D%로 감소시켰습니다.
또한 비동기 복제 환경에서 승인된 쓰기 유실 가능성을 검증하고, 데이터 안전성 설정 적용 전후의 쓰기 p95와 유실 건수를 비교했습니다.
```

면접 예상 질문(초안, 답은 실측 후 작성): Sentinel 이 프록시가 아니라는 게 클라이언트 설계에 주는 영향은? quorum 과 과반의 차이는? Cluster 에서 `cluster-node-timeout` 을 줄이면 무엇이 나빠지나? 성공 응답을 받은 쓰기가 사라지는 걸 어떻게 막았나, 그 대가는? INCR 중복은 왜 생기고 어떻게 없앴나? Hash Tag 로 못 묶는 원자성은 어떻게 처리했나? Sentinel 과 Cluster 중 채팅 서비스에 무엇을 고르겠나, 근거 수치는?

---

## 부록 A. 산출물 목록

Sentinel/Cluster 아키텍처·slot 분포 다이어그램, 정상·Failover·재합류 시퀀스 다이어그램, 장애 전·중·후 Grafana 캡처, Failover 시간 비교표, 승인 쓰기 유실 실험, Hash Tag/CROSSSLOT 실험, 파티션 보고서, ADR(Sentinel vs Cluster, SSE vs WebSocket, 장애 주입 방식, 기록 방식), README, 면접 Q&A. 타임라인 문서 양식은 스펙 17절 그대로 `docs/timelines/TEMPLATE.md` 에 둔다.

## 부록 B. 알려진 위험

- `tc netem` 이 Docker Desktop 커널에서 안 될 수 있음 → 1단계에서 확인함(동작).
- (측정 후 추가) 읽기 전용 설정 + 명령줄 `--replicaof` 는 재시작 시 부트스트랩 역할로 되돌아가 순환 replica 상태를 만든다 → ADR-006.
- Lettuce `replayFilter` 가 Boot 관리 버전에 없으면 재연결 시 재전송 정책은 `disconnectedBehavior` 와 앱 멱등화로만 비교.
- 앱과 Redis 가 한 장비의 VM 을 나눠 쓰므로 2,000 RPS 이상에서 k6·앱 CPU 가 먼저 한계일 수 있음(결과 해석에 명시).
- 폴링 기반 T1~T3 는 ±200 ms 오차. 로그 타임스탬프로 보정.

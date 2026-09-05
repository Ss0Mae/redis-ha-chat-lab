# EXP-OPT. Lettuce 옵션 비교 — 클라이언트 설정이 Failover 결과를 얼마나 바꾸는가

```text
실험 이름: options/<변형>/<시나리오> — Cluster 8 변형(KILL) + TCP_USER_TIMEOUT(pause·앱 파티션), Sentinel 6 변형(KILL) + TCP_USER_TIMEOUT·readFrom(pause·파티션). 각 5회.
검증하려는 가설: (1) Cluster 토폴로지 갱신이 없으면 승격을 알 수 없어 복구까지 실패한다. (2) 재연결 시 버퍼 재전송(DEFAULT)이 유실·중복의 원인이며 REJECT_COMMANDS 로 없앨 수 있다.
                (3) 앱 레벨 재시도는 INCR 중복을 늘린다. (4) 짧은 타임아웃은 실패를 빨리 드러낼 뿐 복구를 앞당기지 않는다.
                (5) hang 형 장애는 TCP_USER_TIMEOUT 으로, Sentinel 승격 추종은 MasterReplica 연결(readFrom)로 고칠 수 있다.
변경한 항목: 앱 컨테이너 환경변수로 Lettuce ClientOptions/ClusterTopologyRefreshOptions 만 변경(코드·Redis·부하 동일). 변형마다 앱 재생성.
통제한 조건·서버 사양·데이터·부하: EXP-A/B 와 같음(300 RPS, 2 분, 5회, 실험마다 FLUSHALL·안정 확인).
```

## 측정 결과 (5회 중앙값 [최소–최대]; `docs/results.md` 4절)

### Cluster (Primary SIGKILL, 기본 = adaptive + periodic 5 s, DEFAULT, timeout 1 s)

| 변형 | 서비스 중단(ms) | 장애 중 오류율 | 승인 쓰기 유실 | INCR 중복 | 복구 후 오류율 | 해석 |
|---|---:|---:|---:|---:|---:|---|
| 기본(adaptive+periodic-5s) | 9,655 [8,002–11,874] | 10.1% | 28 | 27 | 0.00% | 기준 |
| refresh-none (갱신 없음) | 46,535 [8,072–46,589] | 35.9% [8.5–36.8] | 0 [0–32] | 103 [83–372] | 4.7% | 5회 중 3회 복구까지 실패, 2회는 MOVED 를 우연히 받아 8 s |
| periodic-60s (adaptive 없음) | 42,471 [9,948–46,564] | 36.0% [9.9–37.6] | 10 | 87 | 2.6% | 주기 안에 복구가 걸리면 8~10 s, 아니면 다음 주기 |
| adaptive-only (주기 없음) | 47,608 [46,536–47,723] | 36.5% | 0 | 102 | 6.0% | 5회 모두 복구 실패 — 아래 참고 |
| reject-commands | 9,633 [8,040–11,901] | 10.0% | **0** | **0** | 0.00% | 유실·중복이 정확히 0 |
| no-replay-nonidempotent | 9,612 | 9.4% | 31 | 24 | 0.00% | 재전송 필터는 효과 없음 — 아래 참고 |
| retry-all (앱 재시도) | 10,649 | 9.6% | 34 | **66 [42–69]** | 0.00% | 중복 2배 |
| timeout-200ms | 10,824 | 11.2% | **59 [44–81]** | 32 | 0.00% | 유실 2배(버퍼에 쌓이는 명령이 늘어남) |
| tcp-user-timeout-3s, pause | 9,163 [6,935–11,368] | 9.6% | 12 | 14 | 0.00% | pause 기본 10,514 → 소폭 개선 |
| tcp-user-timeout-3s, 앱↔Primary 단절 | 50,845 | 35.4% | 0 | 92 | 10.0% | Failover 가 없는 장애라 효과 없음 |
| adaptive-only + adaptiveRefreshTriggersTimeout 5 s | 11,660 [10,594–11,704] | 11.6% | 40 | 31 | 0.00% | 쿨다운 30 → 5 s 로 5회 모두 복구(47.6 → 11.7 s). periodic 병행(9.7 s)보다 2 s 느림 — 아래 참고 |
| adaptive-only + 쿨다운 5 s + 재연결 트리거 2회 | 12,711 [12,683–12,806] | 13.0% | 42 | 28 | 0.00% | 트리거를 앞당기면 첫 갱신이 더 일찍(FAIL 전) 헛돌아 오히려 1 s 느림 |
| adaptive+periodic-5s + reject (재현) | 9,768 [8,281–12,066] | 10.2% | **0** | **0** | 0.00% | reject-commands 변형(9,633, 0/0)의 재현. 최종 권장 조합 |

### Sentinel (Primary SIGKILL, 기본 = RedisClient 단일 연결, DEFAULT, timeout 1 s)

| 변형 | SIGKILL 중단(ms) | pause 중단(ms) | 장애 중 오류율(KILL) | 유실·중복 | 해석 |
|---|---:|---:|---:|---|---|
| 기본 | 6,566 | 40,180 (98.2%, 유실 1,310) | 13.6% | 0 / 0 | 기준 |
| reject-commands | 6,681 | — | 16.4% | 0 / 0 | 오류가 TIMEOUT 대신 즉시 CONNECTION 으로 바뀜(1 s 씩 기다리지 않음) |
| no-replay-nonidempotent | 6,704 | — | 14.5% | 0 / 0 | 차이 없음 |
| retry-all | 6,566 | — | 11.4% | 0 / 0 [0–1] | 재시도로 읽기 오류율은 줄고 중복은 안 생김(RST 로 끊긴 명령은 재전송되지 않음) |
| timeout-200ms | 7,779 | — | 18.8% | 0 / 0 | 오류가 더 빨리, 더 많이 드러날 뿐 |
| tcp-user-timeout-3s | 6,684 | **40,165 (98.2%, 유실 1,281)** | 14.3% | 0 / 0 | pause 에 효과 없음 — 아래 참고 |
| tcp-user-timeout-3s, 앱↔Primary 단절 | 50,975 | — | 98.6% | 0 / 0 | 복구 후 오류 30.5% → 27.1% 로만 개선 |
| read-from-master (`readFrom=UPSTREAM`, MasterReplica) | **11,111 [11,036–11,149]** | **11,887 (29.6%, 유실 0)** | 27.4% | 0 / 0 | pause 40 → 12 s. 단순 종료는 6.6 → 11.1 s 로 느려짐 |
| read-from-master, Primary↔Sentinel 단절 | 쓰기 실패 없음 | — | 0.0% | 유실 **345 [323–359]** (기본 4,084) | 앱이 `+switch-master` 4.0 s 뒤 새 Primary 로 옮겨 감 |
| read-from-master + tcp-user-timeout-3s, pause | — | 11,621 | 28.4% | 0 / 0 | readFrom 단독과 같음 |
| pool-8 (commons-pool2, 연결 8개) | 6,699 [5,512–6,729] | — | 13.9% | 0 / 0 | 차이 없음: 8개 연결이 모두 같은 Primary 를 향해 함께 끊기고 함께 Sentinel 을 재조회한다. 풀은 처리량 문제이지 Failover 문제가 아니다 |

## 병목 원인·해석

**가설 (1) 확인 — 갱신 없이는 승격을 모른다.** Lettuce Cluster 는 죽은 노드 주소로 재연결을 반복할 뿐, 누군가 slot 맵을 다시 읽어야 새 Primary 로 간다. `refresh-none` 은 5회 중 3회가 복구(40 s)까지 실패했고 2회는 살아 있는 노드에서 `MOVED` 를 받아 그 slot 만 갱신되며 8 s 에 풀렸다(운에 좌우됨). `periodic-60s` 는 주기 안에 들면 8~10 s, 아니면 최대 60 s. 놀랍게도 **adaptive-only 는 5회 모두 실패**했다: 재연결 5회 실패(PERSISTENT_RECONNECTS) 트리거가 kill 뒤 약 5 s 에 갱신을 한 번 돌리는데 그때는 아직 FAIL 판정 전(7 s)이라 토폴로지가 그대로이고, 그 다음 갱신은 `adaptiveRefreshTriggersTimeout`(기본 30 s) 쿨다운에 막혀 35 s 이후에나 가능했다. 그래서 기본값처럼 **adaptive + 짧은 periodic(5 s)** 을 함께 켜야 9.7 s 가 나온다. 쿨다운을 5 s 로 줄인 변형(후속 재측정)은 5회 모두 11.7 s 에 복구돼 이 원인을 확인했다: 첫 갱신은 kill 뒤 약 5 s(FAIL 전)에 헛돌고, 5 s 뒤 두 번째 갱신이 승격(약 8 s)을 잡는다. 재연결 트리거를 2회로 앞당기면 첫 갱신이 더 일찍 헛돌아 12.7 s 로 오히려 느렸다. adaptive 트리거는 "언제 시도하느냐" 가 서버 측 FAIL 확정 시각(node-timeout × 약 1.4)과 맞아야 하고, 그 정렬을 운에 맡기지 않는 방법이 짧은 periodic 이다.

**가설 (2) 확인 — 유실·중복은 복제가 아니라 재전송이었다.** `REJECT_COMMANDS` 로 바꾸자 Cluster SIGKILL 의 유실 28·중복 27 이 **정확히 0** 이 됐고 중단 시간은 같았다(9.6 s). 기본 `DEFAULT` 는 끊긴 동안의 명령을 버퍼에 쌓았다가 재연결 뒤 보낸다. 클라이언트에서는 1 s 뒤 타임아웃으로 실패 처리됐지만, 복구 때 옛 노드와 다시 연결되며 버퍼가 비워지고 `MOVED` 를 따라 새 Primary 에서 실행돼 옛 값으로 덮어쓰거나(잘못된 값 = "유실") INCR 을 한 번 더 했다(중복). `replayFilter` 로 비멱등 명령을 빼는 변형이 효과가 없는 것은 이 필터가 "보냈지만 응답 못 받은 명령의 재전송" 만 걸러내고 "끊긴 동안 버퍼에 들어간 명령" 은 그대로 보내기 때문이다. `timeout-200ms` 에서 유실이 2배(59)가 된 것도 같은 이유다 — 타임아웃이 짧을수록 같은 시간에 더 많은 명령이 실패 처리된 채 버퍼에 남는다. Sentinel 은 SIGKILL 이 RST 로 연결을 끊어 버퍼가 함께 버려지므로 0 이었다.

**가설 (3) 확인.** 앱 레벨 재시도(`retry-all`)는 Cluster 에서 INCR 중복을 27 → 66 으로 늘렸다. Sentinel 에서는 0 이었는데, 첫 시도가 RST 로 즉시 실패하고 재시도도 같은 죽은 노드로 가서 실패했기 때문이다(재시도가 실행되지 않으면 중복도 없다). 읽기(GET/MGET) 오류율만 소폭 줄었다.

**가설 (4) 확인.** 200 ms 타임아웃은 중단 시간을 줄이지 못했고(감지·승격은 서버 쪽 시간) 오류율만 올렸다(Sentinel 13.6 → 18.8%).

**가설 (5) 는 반만 맞았다.** `TCP_USER_TIMEOUT 3 s` 는 Cluster pause 를 10.5 → 9.2 s 로만 줄였고 **Sentinel pause 에는 전혀 효과가 없었다**(40 s 그대로). `docker pause` 는 프로세스를 멈추지만 그 컨테이너의 커널 TCP 스택은 계속 ACK 를 보낸다. 앱이 보낸 명령은 정지된 프로세스의 수신 버퍼에 쌓이며 ACK 되므로 "ACK 없는 데이터" 가 생기지 않고, TCP_USER_TIMEOUT 은 발동하지 않는다. 즉 **응답이 없는 것(애플리케이션 계층)과 ACK 가 없는 것(전송 계층)은 다른 사건**이라 hang 은 응답 타임아웃을 연결 종료로 이어 주는 로직이 있어야 잡힌다.
반면 `readFrom=UPSTREAM` 은 Spring 이 Lettuce `MasterReplica` 연결을 쓰게 만들고, 이 연결은 Sentinel 의 `+switch-master` 를 구독해 승격 즉시 연결을 새 Primary 로 옮긴다. pause 가 40 → 11.9 s, Primary↔Sentinel 단절의 유실이 4,084 → 345 로 줄었다(승격 4.0 s 뒤 옮겨 가기까지의 쓰기만 유실). 대가는 단순 종료에서 6.6 → 11.1 s 로 느려진 것이다: MasterReplica 는 승격 이벤트를 받은 뒤 토폴로지를 다시 읽고 연결을 교체하는 동안 진행 중 명령을 취소(`CancellationException` 300건)하고, 그 과정이 Sentinel 재조회 한 번보다 오래 걸렸다.

## 부작용

- `REJECT_COMMANDS` 는 끊긴 동안의 요청을 즉시 실패시킨다. 정합성은 좋아지지만 "잠깐 기다리면 성공할" 요청까지 실패로 돌려주므로 재시도는 애플리케이션이 멱등하게 해야 한다.
- `readFrom=UPSTREAM` 은 연결 형태를 바꾸는 큰 설정이다. 단순 종료의 복구가 4.5 s 느려지고 취소 오류가 새로 생긴다. hang·파티션이 얼마나 자주 나느냐에 따라 선택이 갈린다.
- 짧은 타임아웃·앱 재시도는 관찰 지표(오류율)만 좋아 보이게 하거나 나쁘게 하고 실제 복구 시간은 바꾸지 못했다.

## 결론

Cluster 는 `adaptive + periodic 5 s + REJECT_COMMANDS` 가 중단 9.6 s·유실 0·중복 0 으로 가장 좋았고(별도 5회 재현 9.8 s·0·0), 갱신 설정이 빠지면 복구까지 실패한다. Sentinel 은 단순 종료라면 기본 연결이 가장 빠르지만(6.6 s), hang·파티션까지 고려하면 `readFrom=UPSTREAM`(MasterReplica) 이 40 s 를 12 s 로 줄이는 유일한 클라이언트 측 해법이었다. `TCP_USER_TIMEOUT` 은 프로세스 정지형 장애를 잡지 못한다.

## 다음 실험

`adaptiveRefreshTriggersTimeout` 을 node-timeout 에 맞춰 자동 계산(예: × 1.5)하면 periodic 없이도 9.7 s 에 닿는지; MasterReplica(readFrom) 연결의 취소 오류 300건을 줄이는 재시도 정책.
